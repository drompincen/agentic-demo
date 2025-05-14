package com.example.agency.orchestrator;

import com.example.agency.bus.Source;
import com.example.agency.util.AgentActivityLogger;
import com.fasterxml.jackson.databind.JsonNode; // For parsing event payload
import com.fasterxml.jackson.databind.ObjectMapper; // For parsing event payload
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.language.LanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException; // For ObjectMapper
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SchedulerAgent {
    private static final Logger log = LoggerFactory.getLogger(SchedulerAgent.class);
    private final Source.FileSource eventBus;
    private boolean groomingCycleTriggeredThisRun = false;
    private boolean userTechChatInitiatedThisRun = false;
    private final ObjectMapper objectMapper = new ObjectMapper(); // For parsing event JSON

    public SchedulerAgent(LanguageModel llm, ChatMemory memory, Source.FileSource eventBus) {
        this.eventBus = eventBus;
        this.eventBus.subscribe(this::onEvent); // Subscribe to the main event bus
        log.info("SchedulerAgent initialized and subscribed to event bus.");
    }

    private void onEvent(String rawEvent) {
        AgentActivityLogger.logAction("SchedulerAgent", "EventBus", "Received event: " + rawEvent);

        if (rawEvent.startsWith("TodayEvent:")) {
            handleTodayEvent(rawEvent);
        } else if (rawEvent.startsWith("NewSlackMessageEvent:")) {
            handleNewSlackMessageEvent(rawEvent);
        } else {
            handleOther(rawEvent);
        }
    }

    private void handleOther(String rawEvent) {
        if(rawEvent.contains("groomingNeeded")){
            AgentActivityLogger.logAction("SchedulerAgent", "TodayEvent", "Triggering GroomingAgent for ALPHA");
            new Source.TaskQueue("GroomingAgent").enqueue("BeginGroomingCycle:{\"projectKey\":\"ALPHA\"}");

        }
    }

    private void handleTodayEvent(String rawTodayEvent) {
        try {
            Instant now = Instant.parse(rawTodayEvent.split(":", 2)[1].trim());
            ZonedDateTime zdt = now.atZone(ZoneId.systemDefault());
            AgentActivityLogger.logAction("SchedulerAgent", "TodayEvent", "Processing for date: " + zdt);

            if (!groomingCycleTriggeredThisRun) { // Simplified for demo
                AgentActivityLogger.logAction("SchedulerAgent", "TodayEvent", "Triggering GroomingAgent for ALPHA");
                new Source.TaskQueue("GroomingAgent").enqueue("BeginGroomingCycle:{\"projectKey\":\"ALPHA\"}");
                groomingCycleTriggeredThisRun = true;
            }

            if (!userTechChatInitiatedThisRun) {
                AgentActivityLogger.logAction("SchedulerAgent", "TodayEvent", "Initiating User-Tech Agent conversation.");
                new Source.TaskQueue("UserAgent").enqueue("StartLoginIssueConversation");
                userTechChatInitiatedThisRun = true;
            }
        } catch (Exception e) {
            AgentActivityLogger.logError("SchedulerAgent", "TodayEvent - " + rawTodayEvent, "Error processing", e);
        }
    }

    private void handleNewSlackMessageEvent(String rawSlackEvent) {
        try {
            String eventJson = rawSlackEvent.substring("NewSlackMessageEvent:".length());
            JsonNode eventNode = objectMapper.readTree(eventJson);
            String channel = eventNode.path("channel").asText();
            String sender = eventNode.path("sender").asText();
            String recipient = eventNode.path("recipient").asText();

            AgentActivityLogger.logAction("SchedulerAgent", "NewSlackMessageEvent",
                    "Routing message from " + sender + " to " + recipient + " on channel " + channel);

            if (recipient != null && !recipient.isEmpty()) {
                String taskForRecipient = String.format(
                        "ProcessNewSlackMessage:{\"channel\":\"%s\", \"originalSender\":\"%s\"}",
                        channel, sender
                );
                new Source.TaskQueue(recipient).enqueue(taskForRecipient);
                AgentActivityLogger.logAction("SchedulerAgent", "NewSlackMessageEvent",
                        "Enqueued task for " + recipient + " to process message from " + sender);
            } else {
                AgentActivityLogger.logError("SchedulerAgent", "NewSlackMessageEvent", "Recipient not found in event: " + rawSlackEvent, null);
            }
        } catch (IOException e) {
            AgentActivityLogger.logError("SchedulerAgent", "NewSlackMessageEvent - " + rawSlackEvent, "Failed to parse event JSON", e);
        }
    }
}