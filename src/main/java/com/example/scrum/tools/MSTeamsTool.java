package com.example.scrum.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap; // For mutable map

public class MSTeamsTool {
    private static final Logger log = LoggerFactory.getLogger(MSTeamsTool.class);
    private final ObjectMapper jsonLineMapper = new ObjectMapper();
    private final Path channelsLogDir = Paths.get("data", "mocks", "msteams", "channels_log");
    public static final String DEFAULT_SENDER_AGENT = "GroomingAgent"; // Agent's "username" in Teams

    public MSTeamsTool() {
        try {
            Files.createDirectories(channelsLogDir);
            log.info("MSTeamsTool initialized. Channel logs will be in: {}", channelsLogDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize MSTeamsTool directories at {}", channelsLogDir.toAbsolutePath(), e);
        }
    }

    // Renamed for clarity: this is what the agent calls to send a message
    public String sendMessage(String channelOrUser, String messageText) {
        // For simulation, "channelOrUser" will be the channel name.
        // If it were a real user, the implementation would differ (e.g., using user ID).
        log.info("MSTeamsTool: Attempting to send message to channel/user '{}': '{}'", channelOrUser, messageText);
        return recordMessageSent(channelOrUser, DEFAULT_SENDER_AGENT, messageText);
    }

    public String recordMessageSent(String channelName, String sender, String messageText) {
        String sanitizedChannelName = channelName.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        if (sanitizedChannelName.isEmpty()) {
            sanitizedChannelName = "unknown_channel";
        }
        Path channelLogFile = channelsLogDir.resolve(sanitizedChannelName + ".jsonl");

        log.info("MSTeamsTool: Recording message from {} to channel #{} in file {}", sender, channelName, channelLogFile.getFileName());
        Map<String, Object> messageData = new HashMap<>(); // Use HashMap for mutability if needed later
        messageData.put("channel", channelName);
        messageData.put("sender", sender);
        messageData.put("text", messageText);
        messageData.put("timestamp", Instant.now().toString());

        try {
            String messageJsonLine = jsonLineMapper.writeValueAsString(messageData) + System.lineSeparator();
            Files.writeString(channelLogFile, messageJsonLine, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            log.info("Message recorded successfully to {}", channelLogFile.toAbsolutePath());
            return "{\"status\": \"success\", \"message\": \"Message sent and recorded to channel " + channelName + ".\"}";
        } catch (IOException e) {
            log.error("Failed to record message to {}: {}", channelLogFile.toAbsolutePath(), e.getMessage(), e);
            return "{\"status\": \"error\", \"message\": \"Failed to send/record message: " + e.getMessage() + "\"}";
        }
    }

    // Reads the last N messages from a channel (not specific to sender)
    public String readChannelHistory(String channelName, int messageCount) {
        String sanitizedChannelName = channelName.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        if (sanitizedChannelName.isEmpty()) sanitizedChannelName = "unknown_channel";
        Path channelLogFile = channelsLogDir.resolve(sanitizedChannelName + ".jsonl");

        if (!Files.exists(channelLogFile)) {
            log.warn("Channel log file not found for reading history: {}", channelLogFile);
            return "{\"status\": \"error\", \"message\": \"Channel log not found: " + channelName + "\"}";
        }

        List<Map<String, Object>> lastMessages = new java.util.ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(channelLogFile);
            int start = Math.max(0, lines.size() - messageCount);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> messageData = jsonLineMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    lastMessages.add(messageData);
                } catch (IOException e) {
                    log.error("Error parsing line from channel log {}: {}", channelLogFile, line, e);
                }
            }
            log.info("MSTeamsTool: Read last {} messages from channel #{}: {}", lastMessages.size(), channelName, lastMessages);
            return jsonLineMapper.writeValueAsString(lastMessages);
        } catch (IOException e) {
            log.error("Error reading channel log file {}: {}", channelLogFile, e);
            return "{\"status\": \"error\", \"message\": \"Error reading channel history: " + e.getMessage() + "\"}";
        }
    }


    // Kept for potential use, but agent might prefer general channel history
    public Map<String, Object> readLastMessageFromSender(String channelName, String expectedSender) {
        String sanitizedChannelName = channelName.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        if (sanitizedChannelName.isEmpty()) sanitizedChannelName = "unknown_channel";
        Path channelLogFile = channelsLogDir.resolve(sanitizedChannelName + ".jsonl");

        if (!Files.exists(channelLogFile)) {
            log.warn("Channel log file not found: {}", channelLogFile);
            return Collections.emptyMap();
        }

        try {
            List<String> lines = Files.readAllLines(channelLogFile);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> messageData = jsonLineMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    if (expectedSender.equals(messageData.get("sender"))) {
                        log.info("MSTeamsTool: Read last message from {} in #{}: {}", expectedSender, channelName, messageData.get("text"));
                        return messageData;
                    }
                } catch (IOException e) {
                    log.error("Error parsing line from channel log {}: {}", channelLogFile, line, e);
                }
            }
        } catch (IOException e) {
            log.error("Error reading channel log file {}: {}", channelLogFile, e);
        }
        log.warn("No message found from sender {} in channel {}", expectedSender, channelName);
        return Collections.emptyMap();
    }
}