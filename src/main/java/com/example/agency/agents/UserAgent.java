package com.example.agency.agents;

import com.example.agency.BaseLlmAgent;
import com.example.agency.bus.Source;
import com.example.scrum.llm.LlamaLanguageModelWrapper;
import com.example.scrum.tools.MSTeamsTool;
import com.example.agency.util.AgentActivityLogger;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

public class UserAgent extends BaseLlmAgent {
    private static final Logger log = LoggerFactory.getLogger(UserAgent.class);
    private final String agentName = "UserAgent";
    private final Source.TaskQueue myQueue;
    private final Source.FileSource eventBus;
    private final MSTeamsTool teamsTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private int solutionsTriedThisConversation = 0;
    private final int MAX_SOLUTIONS_TO_TRY = 3; // Max attempts before "escalation"
    private final String TECH_AGENT_NAME = "TechAgent";
    private static final String SHARED_CHANNEL = "support-channel";
    private boolean conversationActive = false;

    // SYSTEM PROMPT to guide the LLM to act as a frustrated UserAgent
    private static final String SYSTEM_PROMPT_USER_AGENT_FRUSTRATED =
            "YOU ARE UserAgent. You are currently very frustrated because you are having a login issue with 'System A' and TechAgent's suggestions are not working. " +
                    "You are messaging TechAgent on the '" + SHARED_CHANNEL + "' channel. " +
                    "Your response MUST BE ONLY the direct text you send to TechAgent. " +
                    "DO NOT explain yourself, DO NOT use markdown, DO NOT use professional or overly polite language. Show your increasing annoyance and impatience. " +
                    "If a suggestion fails, clearly state it failed and demand a new, better idea. " +
                    "If it's the final attempt, express extreme frustration and state you are escalating.";

    public UserAgent(LanguageModel llm, ChatMemory memory, Source.FileSource eventBus, MSTeamsTool msTeamsTool) {
        super(llm, memory); // ChatMemory will be used to provide context to the LLM
        this.myQueue = new Source.TaskQueue(agentName);
        this.myQueue.subscribe(this::handleTask);
        this.teamsTool = msTeamsTool;
        this.eventBus = eventBus;
        AgentActivityLogger.logAction(agentName, "Initialization", agentName + " initialized (LLM-driven replies, frustrated persona).");
    }

    private String cleanLlmUserAgentMessage(String rawOutput) {
        String message = rawOutput;
        if (message == null) return "This isn't working!"; // Fallback for null

        int eotIndex = message.indexOf("<|eot_id|>");
        if (eotIndex != -1) message = message.substring(0, eotIndex);
        message = message.replaceAll("<\\|.*?\\|>", "").trim();

        // Remove instructions or meta-comments from LLM
        message = message.replaceAll("(?im)^UserAgent says:|^UserAgent would angrily state:|^Okay, my frustrated message is:|^Here's what UserAgent sends:\\s*", "").trim();
        message = message.replaceAll("(?im)^UserAgent:", "").trim();

        // Remove surrounding quotes if the entire message is quoted
        if (message.startsWith("\"") && message.endsWith("\"") && message.length() > 1) {
            message = message.substring(1, message.length() - 1);
        }
        if (message.startsWith("'") && message.endsWith("'") && message.length() > 1) {
            message = message.substring(1, message.length() - 1);
        }
        message = message.trim();

        if (message.isEmpty()) {
            return "This is ridiculous! Still not working! What now?!"; // More frustrated fallback
        }
        return message;
    }

    private void handleTask(String rawTask) {
        AgentActivityLogger.logTaskReceived(agentName, rawTask);

        String userPromptForLLM; // This is the instruction to our LLM on how UserAgent should behave
        String messageToSendToTechAgent;

        if (rawTask.startsWith("StartLoginIssueConversation")) {
            if (conversationActive) {
                AgentActivityLogger.logAction(agentName, rawTask, "Conversation already active. Ignoring.");
                return;
            }
            conversationActive = true;
            solutionsTriedThisConversation = 0;
            this.memory.clear(); // Fresh conversation history for the LLM

            // Initial message is still pre-defined for consistency
            messageToSendToTechAgent = "Hi TechAgent, I'm having trouble logging into System A. My password isn't working even after a reset and I'm seeing an error. Can you help already?!";
            AgentActivityLogger.logAction(agentName, rawTask, "Initiating login issue (frustrated). Sending: '" + messageToSendToTechAgent + "'");

            // Add this first UserAgent message to its own memory as if it said it
            this.memory.add(new UserMessage(messageToSendToTechAgent));

        } else if (rawTask.startsWith("ProcessNewSlackMessage:")) {
            if (!conversationActive) {
                log.warn("{} received ProcessNewSlackMessage but no conversation is active. Ignoring.", agentName);
                return;
            }
            try {
                String taskJson = rawTask.substring("ProcessNewSlackMessage:".length());
                JsonNode taskNode = objectMapper.readTree(taskJson);
                // ... (parsing channel, originalSender as before) ...
                String originalSender = taskNode.path("originalSender").asText();
                if (!TECH_AGENT_NAME.equals(originalSender)) return;


                Map<String, Object> lastMessageData = teamsTool.readLastMessageFromSender(SHARED_CHANNEL, TECH_AGENT_NAME);
                String receivedTextFromTechAgent = (String) lastMessageData.getOrDefault("text", "TechAgent's last suggestion was garbled.");
                AgentActivityLogger.logAction(agentName, rawTask, "Processing TechAgent's suggestion (" + (solutionsTriedThisConversation + 1) + "): '" + receivedTextFromTechAgent + "'");

                // Add TechAgent's message to UserAgent's LLM memory
                this.memory.add(new AiMessage(receivedTextFromTechAgent)); // TechAgent is like the "AI" in this context for UserAgent's LLM

                solutionsTriedThisConversation++;

                if (solutionsTriedThisConversation >= MAX_SOLUTIONS_TO_TRY) {
                    userPromptForLLM = String.format(
                            "You are UserAgent, EXTREMELY frustrated. TechAgent's %dth suggestion ('%s') FAILED. " +
                                    "Send a message to TechAgent on channel '%s' expressing extreme annoyance, stating this is the final attempt, and you're escalating if this doesn't work. Be blunt.",
                            solutionsTriedThisConversation, receivedTextFromTechAgent, SHARED_CHANNEL
                    );
                } else {
                    // For testing, let's assume it "works" on the 2nd try from TechAgent.
                    boolean solutionWorkedThisTime = (solutionsTriedThisConversation == 2);
                    if (solutionWorkedThisTime) {
                        userPromptForLLM = String.format(
                                "You are UserAgent. TechAgent's last suggestion ('%s') FINALLY worked after %d tries. " +
                                        "Send a message to TechAgent on channel '%s' (still a bit annoyed it took so long) confirming it's resolved.",
                                receivedTextFromTechAgent, solutionsTriedThisConversation, SHARED_CHANNEL
                        );
                    } else {
                        userPromptForLLM = String.format(
                                "You are UserAgent, getting more annoyed. TechAgent's suggestion ('%s') FAILED. This was attempt #%d. " +
                                        "Send a message to TechAgent on channel '%s' stating it failed and demanding a DIFFERENT and BETTER idea. Show your impatience.",
                                receivedTextFromTechAgent, solutionsTriedThisConversation, SHARED_CHANNEL
                        );
                    }
                }

                // Now, UserAgent uses its LLM to formulate its (frustrated) reply
                this.memory.add(new SystemMessage(SYSTEM_PROMPT_USER_AGENT_FRUSTRATED)); // Ensure persona for this call
                this.memory.add(new UserMessage(userPromptForLLM)); // This is the instruction for UserAgent's LLM

                dev.langchain4j.model.output.Response<String> llmResponse;
                log.debug("{} sending to LLM with history size {}. User instruction: {}", agentName, this.memory.messages().size(), userPromptForLLM);
                if (this.llm instanceof LlamaLanguageModelWrapper) {
                    // For LlamaLanguageModelWrapper, we need to extract system and user prompts from memory if it expects that.
                    // Or, if it can take full message list, we pass this.memory.messages().
                    // Let's assume LlamaLanguageModelWrapper takes system + user for now.
                    // The userPromptForLLM becomes the "current user message" for the wrapper.
                    // The actual SYSTEM_PROMPT_USER_AGENT_FRUSTRATED is the system prompt.
                    llmResponse = ((LlamaLanguageModelWrapper) this.llm).generate(SYSTEM_PROMPT_USER_AGENT_FRUSTRATED, userPromptForLLM, 70, 0.5f); // Higher temp for more varied frustration
                } else {
                    // Fallback if it's a generic ChatLanguageModel that takes List<ChatMessage>
                    llmResponse = this.llm.generate(this.memory.toString());
                }
                String rawLlmOutput = (llmResponse != null && llmResponse.content() != null) ? llmResponse.content().trim() : "";
                messageToSendToTechAgent = cleanLlmUserAgentMessage(rawLlmOutput);

                // Add UserAgent's own (LLM-generated) message to its memory
                this.memory.add(new UserMessage(messageToSendToTechAgent)); // It's a UserMessage from UserAgent's perspective

            } catch (IOException e) {
                AgentActivityLogger.logError(agentName, rawTask, "Failed to parse ProcessNewSlackMessage task", e);
                return;
            }
        } else {
            log.warn("{} received unknown task: {}", agentName, rawTask);
            return;
        }

        // Send the determined/generated message
        if (messageToSendToTechAgent != null) {
            AgentActivityLogger.logAction(agentName, rawTask, agentName + " sending to " + TECH_AGENT_NAME + ": '" + messageToSendToTechAgent + "'");
            teamsTool.recordMessageSent(SHARED_CHANNEL, agentName, messageToSendToTechAgent);

            String eventPayload = String.format(
                    "{\"channel\":\"%s\", \"sender\":\"%s\", \"recipient\":\"%s\"}",
                    SHARED_CHANNEL, agentName, TECH_AGENT_NAME
            );
            eventBus.publish("NewSlackMessageEvent:" + eventPayload);
            AgentActivityLogger.logAction(agentName, SHARED_CHANNEL, "Published NewSlackMessageEvent for " + TECH_AGENT_NAME);

            if (messageToSendToTechAgent.toLowerCase().contains("resolved") ||
                    (solutionsTriedThisConversation >= MAX_SOLUTIONS_TO_TRY && messageToSendToTechAgent.toLowerCase().contains("escalate"))) {
                AgentActivityLogger.logAction(agentName, SHARED_CHANNEL, agentName + ": Conversation with TechAgent concluded.");
                conversationActive = false;
            }
        }
    }
}