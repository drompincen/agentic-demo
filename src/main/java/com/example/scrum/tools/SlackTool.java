package com.example.scrum.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class SlackTool {
    private static final Logger log = LoggerFactory.getLogger(SlackTool.class);
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path messagesDir = Paths.get("data", "slack", "messages"); // Per-channel logs might be better

    public SlackTool() {
        try {
            Files.createDirectories(messagesDir);
        } catch (IOException e) {
            log.error("Failed to initialize SlackTool directories", e);
        }
    }

    public String sendMessage(String channel, String messageText) {
        log.info("SlackTool: Sending message to channel #{} : {}", channel, messageText);
        String messageId = "msg-" + UUID.randomUUID().toString().substring(0,8) + ".json";
        Path messagePath = messagesDir.resolve(channel + "_" + messageId); // Simple file per message
        Map<String, Object> messageData = Map.of(
                "channel", channel,
                "text", messageText,
                "user", "AutomationAgent", // Or a more specific agent name
                "timestamp", Instant.now().toString()
        );
        try {
            Files.writeString(messagePath, objectMapper.writeValueAsString(messageData), StandardOpenOption.CREATE_NEW);
            return "{\"status\": \"success\", \"message\": \"Message sent to channel #" + channel + "\"}";
        } catch (IOException e) {
            log.error("Error sending Slack message: {}", e.getMessage());
            return "{\"status\": \"error\", \"message\": \"Failed to send Slack message: " + e.getMessage() + "\"}";
        }
    }
}