package com.example.scrum.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class OutlookTool {
    private static final Logger log = LoggerFactory.getLogger(OutlookTool.class);
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final Path sentEmailsLogDir = Paths.get("data", "mocks", "outlook", "sent_emails");
    public static final String DEFAULT_SENDER_EMAIL = "grooming.agent@example.com";

    public OutlookTool() {
        try {
            Files.createDirectories(sentEmailsLogDir);
            log.info("OutlookTool initialized. Sent email logs will be in: {}", sentEmailsLogDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize OutlookTool directories at {}", sentEmailsLogDir.toAbsolutePath(), e);
        }
    }

    public String sendEmail(String to, String subject, String body) {
        log.info("OutlookTool: Attempting to send email to '{}' with subject '{}'", to, subject);

        Path recipientLogFile = sentEmailsLogDir.resolve(to.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".jsonl");

        Map<String, Object> emailData = new HashMap<>();
        emailData.put("from", DEFAULT_SENDER_EMAIL);
        emailData.put("to", to);
        emailData.put("subject", subject);
        emailData.put("body", body);
        emailData.put("timestamp", Instant.now().toString());

        try {
            String emailJsonLine = jsonMapper.writeValueAsString(emailData) + System.lineSeparator();
            Files.writeString(recipientLogFile, emailJsonLine, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            log.info("Email to {} recorded successfully in {}", to, recipientLogFile.toAbsolutePath());
            return "{\"status\": \"success\", \"message\": \"Email sent and recorded to " + to + ".\"}";
        } catch (IOException e) {
            log.error("Failed to record email to {} in {}: {}", to, recipientLogFile.toAbsolutePath(), e.getMessage(), e);
            return "{\"status\": \"error\", \"message\": \"Failed to send/record email: " + e.getMessage() + "\"}";
        }
    }
}