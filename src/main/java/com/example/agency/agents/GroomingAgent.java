package com.example.agency.agents;

import com.example.agency.BaseLlmAgent;
import com.example.agency.bus.Source;
import com.example.agency.agents.context.GroomingAgentContext;
import com.example.agency.handler.IntentHandler;
import com.example.scrum.llm.LlamaLanguageModelWrapper;
import com.example.scrum.tools.JiraTool;
import com.example.scrum.tools.MSTeamsTool;
import com.example.scrum.tools.OutlookTool;
import com.example.agency.util.AgentActivityLogger;
import com.example.agency.util.LlmResponseUtil;
import com.example.agency.util.NaturalLanguageToolParser;
import com.example.agency.util.ToolIntent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.language.LanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public class GroomingAgent extends BaseLlmAgent {
    private static final Logger log = LoggerFactory.getLogger(GroomingAgent.class);
    private final Source.TaskQueue myQueue;
    private final IntentHandler intentHandler;
    private final JiraTool jiraTool;
    private final MSTeamsTool msTeamsTool;
    private final OutlookTool outlookTool;

    private final ObjectMapper localObjectMapper = new ObjectMapper();
    private final Set<String> activeGroomingCycles = new HashSet<>();
    private final String agentName = "GroomingAgent";

    private List<String> successfullyGroomedTicketsInCycle;
    private List<String> ticketsPostedToTeamsForFollowUp;
    private String currentCycleProjectKey;
    private String currentTicketIdForAnalysis;

    private static final String SYSTEM_PROMPT_TICKET_ANALYSIS =
            "You are an agile evangelist. Analyze the provided Jira ticket details. " +
                    "tell me how can this ticket be better phrased or if it is missing information like criteria for success\n" +
                    "show me examples for good tickets";

    private static final String SYSTEM_PROMPT_REPORTING =
            "You are JiraBot. I will provide a summary of an analysis cycle. " +
                    "Your ONLY task is to provide the command to send this summary via MS Teams. " +
                    "Use the exact summary text I provide. Example command: 'Send msteams to channel project_updates_channel: \"THE_SUMMARY_I_PROVIDED\"'. " +
                    "What is your command to send the report?";

    public GroomingAgent(LanguageModel llm, ChatMemory memory,
                         JiraTool jiraTool, OutlookTool outlookTool, MSTeamsTool msTeamsTool) {
        super(llm, memory);
        this.myQueue = new Source.TaskQueue(agentName);
        this.myQueue.subscribe(this::handleTask);
        this.jiraTool = jiraTool;
        this.outlookTool = outlookTool;
        this.msTeamsTool = msTeamsTool;
        this.intentHandler = new IntentHandler(jiraTool, outlookTool, msTeamsTool);
        this.successfullyGroomedTicketsInCycle = new ArrayList<>();
        this.ticketsPostedToTeamsForFollowUp = new ArrayList<>();
        log.info("{} (Ultra Simplified Workflow v3) initialized and subscribed to queue: {}", agentName, agentName);
    }

    private void handleTask(String rawTask) {
        AgentActivityLogger.logTaskReceived(agentName, rawTask);
        this.currentTicketIdForAnalysis = null;

        if (rawTask.startsWith("BeginGroomingCycle")) {
            String projectKey = "ALPHA";
            if (rawTask.contains(":")) {
                try {
                    String taskParamsJson = rawTask.substring(rawTask.indexOf(":") + 1);
                    JsonNode taskParamsNode = localObjectMapper.readTree(taskParamsJson);
                    if (taskParamsNode.has("projectKey")) {
                        projectKey = taskParamsNode.get("projectKey").asText(projectKey);
                    }
                } catch (IOException e) { AgentActivityLogger.logError(agentName, rawTask, "Could not parse projectKey", e); }
            }

            String cycleKey = "BeginGroomingCycle_" + projectKey;
            if (activeGroomingCycles.contains(cycleKey)) {
                AgentActivityLogger.logAction(agentName, rawTask, "Cycle for " + projectKey + " already active. Skipping.");
                return;
            }
            activeGroomingCycles.add(cycleKey);

            this.currentCycleProjectKey = projectKey;
            this.successfullyGroomedTicketsInCycle.clear();
            this.ticketsPostedToTeamsForFollowUp.clear();
            AgentActivityLogger.logCycleStart(agentName, "Jira Ticket Analysis Cycle", projectKey);

            // --- MODIFIED: Call getTicketsForProject ---
            String ticketsJson = jiraTool.getTicketsForProject(projectKey);
            AgentActivityLogger.logToolExecution(agentName, "CycleStart_" + projectKey, "JIRA_getTicketsForProject (direct)", Map.of("projectKey", projectKey), ticketsJson.substring(0, Math.min(ticketsJson.length(),100)));

            List<Map<String, Object>> ticketsToAnalyze = new ArrayList<>();
            try {
                if (ticketsJson != null && !ticketsJson.trim().isEmpty() && ticketsJson.trim().startsWith("[")) {
                    ticketsToAnalyze = localObjectMapper.readValue(ticketsJson, new TypeReference<List<Map<String, Object>>>() {});
                } else {
                    log.warn("Received null, empty, or non-array JSON from getTicketsForProject for project {}. Content snippet: {}", projectKey, ticketsJson != null ? ticketsJson.substring(0, Math.min(ticketsJson.length(), 50)) : "null");
                }
            } catch (IOException e) {
                AgentActivityLogger.logError(agentName, "CycleStart_" + projectKey, "Failed to parse tickets from JiraTool: " + ticketsJson.substring(0, Math.min(ticketsJson.length(),100)), e);
            }

            // --- NEW VALIDATION ---
            if (ticketsToAnalyze.size() < 2 && !"TEST_EMPTY".equals(projectKey)) { // Allow TEST_EMPTY for specific testing
                log.error("CRITICAL_VALIDATION_FAILURE: Project {} has fewer than 2 tickets ({}) for analysis. Exiting application.", projectKey, ticketsToAnalyze.size());
                AgentActivityLogger.logError(agentName, "CycleStart_" + projectKey, "Project has " + ticketsToAnalyze.size() + " tickets, which is less than the required 2. Terminating.", null);
                System.exit(1); // Terminate the application
            }
            // --- END VALIDATION ---

            if (ticketsToAnalyze.isEmpty()) { // This check remains
                AgentActivityLogger.logNoTicketsFound(agentName, projectKey); // This log might be confusing if validation above passed for test
                prepareAndSendReport("CycleEnd_" + projectKey);
            } else {
                AgentActivityLogger.logAction(agentName, "CycleStart_" + projectKey, "Found " + ticketsToAnalyze.size() + " tickets for analysis.");
                processNextTicketInQueue(new ArrayList<>(ticketsToAnalyze), "CycleProcessing_" + projectKey);
            }
        } else {
            AgentActivityLogger.logAction(agentName, rawTask, "Received unclassified/unsupported task type: " + rawTask + ". Ignoring.");
        }
    }

    // processNextTicketInQueue, processLLMInteraction, prepareAndSendReport
    // remain largely the same as the previous "Simplified Workflow v2" version,
    // focusing on the "analyze or set groomed" flow for each ticket.
    // Key is that processNextTicketInQueue now receives ALL tickets for the project.

    private void processNextTicketInQueue(List<Map<String, Object>> pendingTickets, String cycleContext) {
        if (this.currentTicketIdForAnalysis != null &&
                !this.successfullyGroomedTicketsInCycle.contains(this.currentTicketIdForAnalysis) &&
                !this.ticketsPostedToTeamsForFollowUp.stream().anyMatch(s -> s.startsWith(this.currentTicketIdForAnalysis))) {
            log.info("AGENT: Ticket {} processing concluded; not marked fully groomed. Adding to follow-up list.", this.currentTicketIdForAnalysis);
            this.ticketsPostedToTeamsForFollowUp.add(this.currentTicketIdForAnalysis + " (needs manual review or action not taken)");
        }
        this.currentTicketIdForAnalysis = null;

        List<Map<String, Object>> currentPendingList = (pendingTickets == null) ? new ArrayList<>() : new ArrayList<>(pendingTickets);

        if (!currentPendingList.isEmpty()) {
            Map<String, Object> ticketToAnalyzeMap = currentPendingList.remove(0);
            String ticketId = (String) ticketToAnalyzeMap.get("id");
            this.currentTicketIdForAnalysis = ticketId;

            AgentActivityLogger.logGroomingTicket(agentName, ticketId, (String)ticketToAnalyzeMap.getOrDefault("summary", "N/A"));

            String detailsJson = jiraTool.getTicketDetails(ticketId);
            AgentActivityLogger.logToolExecution(agentName, "AnalyzeTicket_"+ticketId, "JIRA_getTicketDetails (direct)", Map.of("ticketId", ticketId),
                    (detailsJson.length() > 100 ? detailsJson.substring(0,97)+"..." : detailsJson));

            if (detailsJson.contains("error:") || detailsJson.contains("\"error\"")) {
                AgentActivityLogger.logError(agentName, "AnalyzeTicket_"+ticketId, "Failed to get details for " + ticketId + ". Details: " + detailsJson, null);
                this.ticketsPostedToTeamsForFollowUp.add(ticketId + " (failed to get details)");
                processNextTicketInQueue(currentPendingList, cycleContext);
                return;
            }

            String userPromptForLlm = String.format(
                    "Analyze ticket '%s'. Details: \n```json\n%s\n```\n" +
                            "If this ticket is perfectly ready (has Description, Assignee, Story Points > 0 unless bug), respond ONLY with: 'Set needsGrooming for ticket %s to false.' " +
                            "Otherwise, concisely describe what's missing or needs clarification.",
                    ticketId, detailsJson, ticketId);
            processLLMInteraction(userPromptForLlm, "AnalyzeTicket_" + ticketId, currentPendingList, ticketId);
        } else {
            AgentActivityLogger.logAction(agentName, cycleContext, "All tickets in current batch processed for project " + this.currentCycleProjectKey + ". Preparing summary report.");
            prepareAndSendReport(cycleContext);
        }
    }

    private void processLLMInteraction(String currentUserMessageText,
                                       String originalTaskContext,
                                       List<Map<String, Object>> remainingTickets,
                                       String ticketIdForThisInteraction) {
        String systemPromptText = originalTaskContext.startsWith("ReportFor_") ?
                SYSTEM_PROMPT_REPORTING :
                SYSTEM_PROMPT_TICKET_ANALYSIS;

        this.memory.clear();
        this.memory.add(new SystemMessage(systemPromptText));
        this.memory.add(new UserMessage(currentUserMessageText));

        log.debug("AGENT: Sending to LLM. Context: {}, Ticket: {}, User (snippet): [{}...]",
                originalTaskContext, ticketIdForThisInteraction != null ? ticketIdForThisInteraction : "N/A",
                currentUserMessageText.substring(0, Math.min(currentUserMessageText.length(),120)));

        dev.langchain4j.model.output.Response<String> llmResponse;
        int maxNewTokens = originalTaskContext.startsWith("ReportFor_") ? 100 : 150;

        if (this.llm instanceof LlamaLanguageModelWrapper) {
            llmResponse = ((LlamaLanguageModelWrapper) this.llm).generate(systemPromptText, currentUserMessageText, maxNewTokens, 0.1f);
        } else {
            String combinedPrompt = "SYSTEM:\n" + systemPromptText + "\n\nUSER:\n" + currentUserMessageText + "\n\nASSISTANT:\n";
            llmResponse = this.llm.generate(combinedPrompt);
        }

        String llmOutputText = LlmResponseUtil.getCleanedTextForParsing(llmResponse, ticketIdForThisInteraction);

        if (llmOutputText.isEmpty()) {
            AgentActivityLogger.logError(agentName, originalTaskContext, "LLM returned empty/unprocessable response for " + (ticketIdForThisInteraction != null ? ticketIdForThisInteraction : "reporting task"), null);
            if (ticketIdForThisInteraction != null) {
                this.ticketsPostedToTeamsForFollowUp.add(ticketIdForThisInteraction + " (LLM error/empty response)");
            }
            if (originalTaskContext.startsWith("ReportFor_")) {
                AgentActivityLogger.logCycleComplete(agentName, "Jira Ticket Cycle", this.currentCycleProjectKey, "Failed to get report sending command from LLM.");
                activeGroomingCycles.remove("BeginGroomingCycle_" + this.currentCycleProjectKey);
            } else {
                processNextTicketInQueue(remainingTickets, "CycleProcessing_" + this.currentCycleProjectKey);
            }
            return;
        }
        this.memory.add(new AiMessage(llmResponse.content() != null ? llmResponse.content() : ""));
        log.info("AGENT: LLM response processed for {}: [{}]", originalTaskContext, llmOutputText);

        ToolIntent intent = NaturalLanguageToolParser.parse(llmOutputText);

        if (intent.isToolIdentified()) {
            GroomingAgentContext agentContext = new GroomingAgentContext(this.agentName, originalTaskContext, ticketIdForThisInteraction, this.currentCycleProjectKey, this.successfullyGroomedTicketsInCycle, this.ticketsPostedToTeamsForFollowUp, remainingTickets);
            String toolResult = intentHandler.executeIntent(intent, agentContext);
            AgentActivityLogger.logToolExecution(agentName, originalTaskContext, intent.toolName(), intent.arguments(), toolResult.substring(0, Math.min(toolResult.length(),100)));

            if (originalTaskContext.startsWith("ReportFor_")) {
                AgentActivityLogger.logCycleComplete(agentName, "Jira Ticket Cycle", this.currentCycleProjectKey, "Summary report sent via " + intent.toolName());
                activeGroomingCycles.remove("BeginGroomingCycle_" + this.currentCycleProjectKey);
            } else if ("JIRA_updateTicket".equals(intent.toolName()) && "needsGrooming".equals(intent.arguments().get("fieldName"))) {
                String ticketIdUpdated = (String) intent.arguments().get("ticketId");
                boolean valueSetToFalse = "false".equalsIgnoreCase(String.valueOf(intent.arguments().get("value")));
                if (valueSetToFalse) {
                    AgentActivityLogger.logTicketGroomingComplete(agentName, ticketIdUpdated, "Marked 'needsGrooming=false' by LLM.");
                    successfullyGroomedTicketsInCycle.add(ticketIdUpdated);
                    ticketsPostedToTeamsForFollowUp.removeIf(entry -> entry.startsWith(ticketIdUpdated));
                } else {
                    if(!ticketsPostedToTeamsForFollowUp.stream().anyMatch(s->s.startsWith(ticketIdUpdated))) {
                        ticketsPostedToTeamsForFollowUp.add(ticketIdUpdated + " (LLM set needsGrooming=true or other update)");
                    }
                }
                processNextTicketInQueue(remainingTickets, "CycleProcessing_" + this.currentCycleProjectKey);
            } else { // Other explicit tool command by LLM (e.g., update description, add comment)
                log.info("AGENT: LLM took specific action on ticket {}. Re-analyzing after action.", ticketIdForThisInteraction);
                String detailsJson = jiraTool.getTicketDetails(ticketIdForThisInteraction);
                String nextUserPrompt = String.format(
                        "Task: Re-analyze ticket '%s' after your last action (Tool: %s, Result: %s). Here are the updated details: \n```json\n%s\n```\n" +
                                "If it's now perfectly ready, command 'Set needsGrooming for ticket %s to false.' Otherwise, describe what's still needed.",
                        ticketIdForThisInteraction, intent.toolName(), toolResult.substring(0, Math.min(toolResult.length(), 50)), detailsJson, ticketIdForThisInteraction);
                processLLMInteraction(nextUserPrompt, "AnalyzeTicket_" + ticketIdForThisInteraction, remainingTickets, ticketIdForThisInteraction);
            }
        } else { // No specific tool intent identified by parser
            if (originalTaskContext.startsWith("ReportFor_")) {
                AgentActivityLogger.logError(agentName, originalTaskContext, "LLM failed to provide a command to send the report. Output: " + llmOutputText, null);
                String fallbackMessage = "Fallback Report for " + this.currentCycleProjectKey + ": " + extractSummaryFromPreviousUserMessage();
                String defaultReportChannel = "project_" + this.currentCycleProjectKey.toLowerCase() + "_updates";
                msTeamsTool.sendMessage(defaultReportChannel, fallbackMessage);
                AgentActivityLogger.logAction(agentName, originalTaskContext, "Sent report via fallback due to LLM command failure.");
                AgentActivityLogger.logCycleComplete(agentName, "Jira Ticket Cycle", this.currentCycleProjectKey, "Summary report sent via fallback.");
                activeGroomingCycles.remove("BeginGroomingCycle_" + this.currentCycleProjectKey);
                return;
            }

            if (ticketIdForThisInteraction != null) {
                log.info("AGENT: No tool command for ticket {}. Posting LLM analysis to Teams: [{}]", ticketIdForThisInteraction, llmOutputText);
                String teamsMessage = String.format("Ticket %s needs follow-up. LLM Analysis: %s", ticketIdForThisInteraction, llmOutputText);
                String defaultChannel = "project_" + this.currentCycleProjectKey.toLowerCase() + "_grooming_followups";

                this.msTeamsTool.sendMessage(defaultChannel, teamsMessage);
                AgentActivityLogger.logAction(agentName, "AnalyzeTicket_" + ticketIdForThisInteraction, "Posted LLM analysis for " + ticketIdForThisInteraction + " to Teams: " + defaultChannel);

                if (!ticketsPostedToTeamsForFollowUp.stream().anyMatch(s -> s.startsWith(ticketIdForThisInteraction))) {
                    ticketsPostedToTeamsForFollowUp.add(ticketIdForThisInteraction + " (analysis posted: " + llmOutputText.substring(0, Math.min(llmOutputText.length(), 50)) + "...)");
                }
            } else {
                log.warn("AGENT: No specific ticket in context for natural language output from LLM: {}", llmOutputText);
                AgentActivityLogger.logError(agentName, originalTaskContext, "LLM output was natural language but no ticket was in context.", null);
                //This state should ideally not be reached if currentTicketIdForAnalysis is managed well
            }
            processNextTicketInQueue(remainingTickets, "CycleProcessing_" + this.currentCycleProjectKey);
        }
    }

    private String extractSummaryFromPreviousUserMessage() {
        List<ChatMessage> messages = this.memory.messages();
        for (int i = messages.size() -1; i >=0; i--) { // Iterate backwards to find the last user message
            ChatMessage msg = messages.get(i);
            if (msg instanceof UserMessage) {
                String text = ((UserMessage) msg).singleText();
                String startMarker = "Here is the grooming cycle summary: \"";
                String endMarker = "\". What is your command";
                int startIndex = text.indexOf(startMarker);
                int endIndex = text.indexOf(endMarker);
                if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                    return text.substring(startIndex + startMarker.length(), endIndex);
                }
                break; // Found last user message, no need to look further
            }
        }
        return "Summary could not be extracted.";
    }

    private void prepareAndSendReport(String cycleContext) {
        String reportContextPrefix = "ReportFor_";
        String projectKey = this.currentCycleProjectKey;
        if (projectKey == null) {
            log.error("CRITICAL: currentCycleProjectKey is null during report preparation for context {}", cycleContext);
            activeGroomingCycles.remove("BeginGroomingCycle_ALPHA"); // Attempt cleanup
            return;
        }

        if (cycleContext.startsWith(reportContextPrefix + projectKey) && this.memory.messages().size() > 1 && !(this.memory.messages().getLast() instanceof UserMessage)) {
            log.warn("Reporting already attempted for {}, LLM failed. Not re-preparing.", projectKey);
            AgentActivityLogger.logCycleComplete(agentName, "Jira Ticket Cycle", projectKey, "Failed to get report command after summary.");
            activeGroomingCycles.remove("BeginGroomingCycle_" + projectKey);
            return;
        }

        // Ensure the very last ticket being worked on (if any) is considered for follow-up
        if (this.currentTicketIdForAnalysis != null &&
                !successfullyGroomedTicketsInCycle.contains(this.currentTicketIdForAnalysis) &&
                !ticketsPostedToTeamsForFollowUp.stream().anyMatch(s -> s.startsWith(this.currentTicketIdForAnalysis))) {
            ticketsPostedToTeamsForFollowUp.add(this.currentTicketIdForAnalysis + " (cycle ended)");
        }
        this.currentTicketIdForAnalysis = null;

        StringBuilder reportContent = new StringBuilder();
        reportContent.append("Jira Ticket Analysis Cycle for project ").append(projectKey).append(" summary: ");
        if (successfullyGroomedTicketsInCycle.isEmpty() && ticketsPostedToTeamsForFollowUp.isEmpty()) {
            reportContent.append("No tickets were found or no actions/analysis were performed in this cycle.");
        } else {
            if (!successfullyGroomedTicketsInCycle.isEmpty()) {
                reportContent.append("Tickets marked as ready: [").append(String.join(", ", successfullyGroomedTicketsInCycle)).append("]. ");
            }
            if (!ticketsPostedToTeamsForFollowUp.isEmpty()) {
                reportContent.append("Tickets with analysis posted for follow-up: [").append(String.join(", ", ticketsPostedToTeamsForFollowUp)).append("].");
            }
        }
        AgentActivityLogger.logAction(agentName, "ReportGeneration", "Final Report for " + projectKey + ": " + reportContent.toString());

        String defaultReportChannel = "project_" + projectKey.toLowerCase() + "_updates";
        String reportPrompt = String.format(
                "Here is the grooming cycle summary: \"%s\". " +
                        "What is your command to send this exact summary to MS Teams channel '%s'?",
                reportContent.toString(), defaultReportChannel
        );
        processLLMInteraction(reportPrompt, reportContextPrefix + projectKey, null, null);
    }
}