package com.example.agency.agents;

import com.example.agency.BaseLlmAgent;
import com.example.agency.bus.Source;
import com.example.scrum.llm.LlamaLanguageModelWrapper; // Ensure this is the correct wrapper
import com.example.scrum.tools.MSTeamsTool;
import com.example.agency.util.AgentActivityLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.language.LanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class TechAgent extends BaseLlmAgent {
    private static final Logger log = LoggerFactory.getLogger(TechAgent.class);
    private final String agentName = "TechAgent";
    private final Source.TaskQueue myQueue;
    private final Source.FileSource eventBus;
    private final MSTeamsTool teamsTool;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String USER_AGENT_NAME = "UserAgent";
    private static final String SHARED_CHANNEL = "support-channel";

    private static final String SYSTEM_PROMPT =
            "YOU ARE TechAgent on channel '" + SHARED_CHANNEL + "'. You are helping UserAgent with a login issue. " +
                    "Your response MUST be ONLY the direct text of the *single message* you send back to UserAgent. " +
                    "This message should be a concrete, actionable troubleshooting step OR a single clarifying question. " +
                    "UserAgent will tell you if your previous suggestion failed; if so, offer a NEW, DIFFERENT idea. " +
                    "DO NOT greet, sign-off, explain yourself, or use markdown. Just the troubleshooting message or question. Be concise.";

    public TechAgent(LanguageModel llm, ChatMemory memory, Source.FileSource eventBus, MSTeamsTool msTeamsTool) {
        super(llm, memory);
        this.myQueue = new Source.TaskQueue(agentName);
        this.myQueue.subscribe(this::handleTask);
        this.teamsTool = msTeamsTool;
        this.eventBus = eventBus;
        AgentActivityLogger.logAction(agentName, "Initialization", agentName + " initialized.");
    }

    private String cleanLlmMessage(String rawOutput, String userAgentLastMessage) {
        String message = rawOutput;
        int eotIndex = message.indexOf("<|eot_id|>");
        if (eotIndex != -1) message = message.substring(0, eotIndex);
        message = message.replaceAll("<\\|.*?\\|>", "").trim();
        message = message.replaceAll("(?im)^TechAgent should reply with:|^TechAgent's response:|^As TechAgent, I would suggest:|^My suggestion is:|^You should tell UserAgent:|^Here's a troubleshooting step:\\s*", "").trim();
        message = message.replaceAll("(?im)^TechAgent:|^Message to UserAgent:\\s*", "").trim();
        if (message.startsWith("\"") && message.endsWith("\"") && message.length() > 1) {
            message = message.substring(1, message.length() - 1);
        }
        if (message.startsWith("'") && message.endsWith("'") && message.length() > 1) {
            message = message.substring(1, message.length() - 1);
        }
        message = message.trim();
        if (message.isEmpty()) {
            log.warn("{} LLM produced empty message after cleaning for UserAgent's message: '{}'. Using fallback.", agentName, userAgentLastMessage);
            if (userAgentLastMessage.toLowerCase().contains("help") || userAgentLastMessage.toLowerCase().contains("problem")) {
                return "Okay, I can help with that. Could you please tell me the exact error message you are seeing?";
            } else if (userAgentLastMessage.toLowerCase().contains("didn't work") || userAgentLastMessage.toLowerCase().contains("failed")) {
                return "I see. Let's try something else. Have you tried clearing your browser cache and cookies?";
            } else {
                return "Can you please provide more details about the issue?";
            }
        }
        return message;
    }

    private void handleTask(String rawTask) {
        AgentActivityLogger.logTaskReceived(agentName, rawTask);
        // No need to clear memory here if each interaction is stateless for the LLM call
        // this.memory.clear();
        // this.memory.add(new SystemMessage(SYSTEM_PROMPT)); // System prompt is passed directly

        String userAgentMessageText;

        if (rawTask.startsWith("ProcessNewSlackMessage:")) {
            try {
                String taskJson = rawTask.substring("ProcessNewSlackMessage:".length());
                JsonNode taskNode = objectMapper.readTree(taskJson);
                String channel = taskNode.path("channel").asText();
                String originalSender = taskNode.path("originalSender").asText();

                if (!SHARED_CHANNEL.equals(channel) || !USER_AGENT_NAME.equals(originalSender)) {
                    log.warn("{} received message not from {} or not on {}. Ignoring.", agentName, USER_AGENT_NAME, SHARED_CHANNEL);
                    return;
                }

                Map<String, Object> lastMessageData = teamsTool.readLastMessageFromSender(SHARED_CHANNEL, USER_AGENT_NAME);
                userAgentMessageText = (String) lastMessageData.getOrDefault("text", "UserAgent's message was unclear or not found.");
                AgentActivityLogger.logAction(agentName, rawTask, "Processing message from " + originalSender + ": '" + userAgentMessageText + "'");

                if (userAgentMessageText.toLowerCase().contains("issue is resolved")) {
                    AgentActivityLogger.logAction(agentName, rawTask, "UserAgent indicates issue resolved. No reply needed from TechAgent.");
                    return;
                }
                if (userAgentMessageText.toLowerCase().contains("escalate this")) {
                    AgentActivityLogger.logAction(agentName, rawTask, "UserAgent is escalating. No further troubleshooting reply needed from TechAgent.");
                    return;
                }
            } catch (IOException e) {
                AgentActivityLogger.logError(agentName, rawTask, "Failed to parse ProcessNewSlackMessage task", e);
                return;
            }
        } else {
            log.warn("{} received unknown task: {}", agentName, rawTask);
            return;
        }

        String llmUserPrompt = String.format(
                "UserAgent on channel '%s' sent this message: \"%s\". " +
                        "What is your concise, direct troubleshooting step or clarifying question to send back as TechAgent? Remember to offer a new idea if they said a previous one failed.",
                SHARED_CHANNEL, userAgentMessageText
        );

        // --- CORRECTED LLM CALL ---
        dev.langchain4j.model.output.Response<String> llmResponse;
        if (this.llm instanceof LlamaLanguageModelWrapper) {
            // Use the specific generate method of LlamaLanguageModelWrapper
            llmResponse = ((LlamaLanguageModelWrapper) this.llm).generate(SYSTEM_PROMPT, llmUserPrompt, 70, 0.2f); // Max new tokens, temp
        } else {
            // Fallback for generic LanguageModel (less ideal as it combines prompts)
            log.warn("{} LLM is not LlamaLanguageModelWrapper. Combining prompts.", agentName);
            this.memory.clear(); // Clear for combined prompt
            this.memory.add(new SystemMessage(SYSTEM_PROMPT));
            this.memory.add(new UserMessage(llmUserPrompt));
            llmResponse = this.llm.generate(this.memory.messages().toString()); // This was the error line
        }
        // --- END CORRECTION ---

        String rawLlmMessageOutput = (llmResponse != null && llmResponse.content() != null) ? llmResponse.content().trim() : "";

        String messageToSendToUserAgent = cleanLlmMessage(rawLlmMessageOutput, userAgentMessageText);
        AgentActivityLogger.logAction(agentName, rawTask, agentName + " formulated message: '" + messageToSendToUserAgent + "'");

        teamsTool.recordMessageSent(SHARED_CHANNEL, agentName, messageToSendToUserAgent);

        String eventPayload = String.format(
                "{\"channel\":\"%s\", \"sender\":\"%s\", \"recipient\":\"%s\"}",
                SHARED_CHANNEL, agentName, USER_AGENT_NAME
        );
        eventBus.publish("NewSlackMessageEvent:" + eventPayload);
        AgentActivityLogger.logAction(agentName, SHARED_CHANNEL, "Published NewSlackMessageEvent for " + USER_AGENT_NAME);
    }
}