package com.example.scrum.tools;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JiraTool {
    private static final Logger log = LoggerFactory.getLogger(JiraTool.class);
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path mockBaseDir = Paths.get("data", "mocks");
    private final Path issuesFile = mockBaseDir.resolve(Paths.get("jira", "issues.json"));
    private final Path commentsFile = mockBaseDir.resolve(Paths.get("jira", "comments.jsonl"));
    private final Path transitionsFile = mockBaseDir.resolve(Paths.get("jira", "transitions.jsonl"));

    public JiraTool() {
        try {
            log.info("JiraTool attempting to use issues file at resolved path: {}", issuesFile.toAbsolutePath().toString());
            Files.createDirectories(issuesFile.getParent());
            Files.createDirectories(commentsFile.getParent());
            Files.createDirectories(transitionsFile.getParent());

            if (!Files.exists(commentsFile)) Files.createFile(commentsFile);
            if (!Files.exists(transitionsFile)) Files.createFile(transitionsFile);
            if (!Files.exists(issuesFile)) {
                log.warn("Mock issues file {} does not exist. Creating an empty one.", issuesFile.toAbsolutePath());
                Files.writeString(issuesFile, "[]", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            log.error("Failed to initialize JiraTool directories or ensure mock files exist", e);
        }
    }

    private List<Map<String, Object>> readAllIssues() throws IOException {
        if (!Files.exists(issuesFile)) {
            log.warn("Issues file {} not found. Returning empty list.", issuesFile.toAbsolutePath());
            return new ArrayList<>();
        }
        String content = Files.readString(issuesFile);
        log.debug("JiraTool: Content read from issues.json (first 500 chars): {}", content.substring(0, Math.min(content.length(), 500)));

        if (content.trim().isEmpty() || !content.trim().startsWith("[")) {
            log.warn("Issues file {} is empty or not a valid JSON array. Returning empty list. Content snippet: '{}'", issuesFile.toAbsolutePath(), content.substring(0, Math.min(content.length(), 50)));
            return new ArrayList<>();
        }
        try {
            List<Map<String,Object>> issues = objectMapper.readValue(content, new TypeReference<List<Map<String, Object>>>() {});
            log.info("JiraTool: Successfully parsed {} issues from issues.json", issues.size());
            return issues;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { // Be more specific
            log.error("JiraTool: Failed to parse issues.json content. Error: {}. Content snippet: {}", e.getMessage(), content.substring(0, Math.min(content.length(), 200)));
            return new ArrayList<>();
        }
    }

    // writeAllIssues, readAllCommentsForTicket, getTicketDetails, updateTicket, addComment, addTransition remain the same
    // ... (Paste those methods from the previous complete JiraTool version here) ...
    private void writeAllIssues(List<Map<String, Object>> issues) throws IOException {
        if (!Files.exists(issuesFile.getParent())) {
            Files.createDirectories(issuesFile.getParent());
        }
        Files.writeString(issuesFile, objectMapper.writeValueAsString(issues), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    private List<Map<String, Object>> readAllCommentsForTicket(String ticketId) throws IOException {
        if (!Files.exists(commentsFile)) {
            return new ArrayList<>();
        }
        try (Stream<String> lines = Files.lines(commentsFile)) {
            return lines
                    .filter(line -> !line.trim().isEmpty())
                    .map(line -> {
                        try {
                            return objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                        } catch (IOException e) {
                            log.warn("Failed to parse comment line: {}. Error: {}", line, e.getMessage());
                            return null;
                        }
                    })
                    .filter(commentMap -> commentMap != null && ticketId.equalsIgnoreCase((String) commentMap.get("issueId")))
                    .collect(Collectors.toList());
        }
    }

    public String getTicketDetails(String ticketId) {
        log.info("JiraTool: Getting details for ticket {}", ticketId);
        try {
            List<Map<String, Object>> allIssues = readAllIssues();
            Optional<Map<String, Object>> ticketOpt = allIssues.stream()
                    .filter(issue -> issue != null && ticketId.equalsIgnoreCase((String) issue.get("id")))
                    .findFirst();

            if (ticketOpt.isPresent()) {
                Map<String, Object> ticketData = new HashMap<>(ticketOpt.get());
                List<Map<String, Object>> commentsForTicket = readAllCommentsForTicket(ticketId);
                ticketData.put("comments", commentsForTicket);
                return objectMapper.writeValueAsString(ticketData);
            }
            log.warn("JiraTool: Ticket {} not found in issues.json", ticketId);
            return "{\"error\": \"Ticket " + ticketId + " not found\"}";
        } catch (IOException e) {
            log.error("Error reading or parsing data for getTicketDetails ticket {}: {}", ticketId, e.getMessage(), e);
            return "{\"error\": \"Could not read ticket " + ticketId + ": " + e.getMessage() + "\"}";
        }
    }
    public String updateTicket(String ticketId, String fieldName, Object value) {
        log.info("JiraTool: Updating ticket {} field {} to '{}'", ticketId, fieldName, value);
        try {
            List<Map<String, Object>> allIssues = readAllIssues();
            Optional<Map<String, Object>> ticketOpt = allIssues.stream()
                    .filter(issue -> issue != null && ticketId.equalsIgnoreCase((String) issue.get("id")))
                    .findFirst();

            if (ticketOpt.isPresent()) {
                Map<String, Object> ticketToUpdate = ticketOpt.get();
                Object oldValue = ticketToUpdate.get(fieldName);
                ticketToUpdate.put(fieldName, value);

                if ("status".equalsIgnoreCase(fieldName) && oldValue != null) {
                    addTransition(ticketId, String.valueOf(oldValue), String.valueOf(value), "GroomingAgent");
                }
                writeAllIssues(allIssues);
                return "{\"status\": \"success\", \"message\": \"Ticket " + ticketId + " updated.\"}";
            }
            log.warn("JiraTool: Ticket {} not found for update.", ticketId);
            return "{\"status\": \"error\", \"message\": \"Ticket " + ticketId + " not found for update\"}";
        } catch (IOException e) {
            log.error("Error updating ticket {}: {}", ticketId, e.getMessage(), e);
            return "{\"status\": \"error\", \"message\": \"Could not update ticket " + ticketId + ": " + e.getMessage() + "\"}";
        }
    }
    public String addComment(String ticketId, String author, String body) {
        log.info("JiraTool: Adding comment to ticket {} by {}: '{}'", ticketId, author, body);
        try {
            List<Map<String, Object>> allIssues = readAllIssues();
            boolean ticketExists = allIssues.stream().anyMatch(issue -> issue != null && ticketId.equalsIgnoreCase((String) issue.get("id")));
            if (!ticketExists) {
                log.warn("JiraTool: Attempted to add comment to non-existent ticket {}", ticketId);
                return "{\"status\": \"error\", \"message\": \"Ticket " + ticketId + " not found. Cannot add comment.\"}";
            }
        } catch (IOException e) {
            log.error("JiraTool: Error checking if ticket {} exists before adding comment: {}", ticketId, e.getMessage(), e);
            return "{\"status\": \"error\", \"message\": \"Could not verify ticket " + ticketId + " existence: " + e.getMessage() + "\"}";
        }

        Map<String, Object> comment = Map.of(
                "issueId", ticketId, "author", author, "body", body, "timestamp", Instant.now().toString()
        );
        try {
            String commentJson = objectMapper.writeValueAsString(comment) + System.lineSeparator();
            Files.writeString(commentsFile, commentJson, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            return "{\"status\": \"success\", \"message\": \"Comment added to " + ticketId + "\"}";
        } catch (IOException e) {
            log.error("Error adding comment to {}: {}", ticketId, e.getMessage(), e);
            return "{\"status\": \"error\", \"message\": \"Could not add comment: " + e.getMessage() + "\"}";
        }
    }

    private void addTransition(String ticketId, String fromStatus, String toStatus, String user) {
        Map<String, Object> transition = Map.of(
                "issueId", ticketId, "fromStatus", fromStatus, "toStatus", toStatus, "user", user, "timestamp", Instant.now().toString()
        );
        try {
            String transitionJson = objectMapper.writeValueAsString(transition) + System.lineSeparator();
            Files.writeString(transitionsFile, transitionJson, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            log.info("Logged transition for {}: {} -> {} by {}", ticketId, fromStatus, toStatus, user);
        } catch (IOException e) {
            log.error("Error logging transition for {}: {}", ticketId, e.getMessage(), e);
        }
    }


    // --- SIMPLIFIED METHOD: Get ALL tickets for a project ---
    public String getTicketsForProject(String projectKeyToFilter) {
        log.info("JiraTool: Getting ALL tickets for project Key: '{}'", projectKeyToFilter);
        try {
            List<Map<String, Object>> allIssues = readAllIssues();
            if (allIssues.isEmpty()) {
                log.warn("JiraTool: No issues found in issues.json when getting tickets for project {}", projectKeyToFilter);
                return "[]"; // Return empty JSON array
            }

            List<Map<String, Object>> projectTickets = allIssues.stream()
                    .filter(issue -> {
                        if (issue == null) return false;
                        Object pkObj = issue.get("projectKey");
                        return pkObj instanceof String && projectKeyToFilter.equalsIgnoreCase((String) pkObj);
                    })
                    .map(issue -> { // Return only id and summary, as before
                        Map<String, Object> summaryMap = new HashMap<>();
                        summaryMap.put("id", issue.get("id"));
                        summaryMap.put("summary", issue.get("summary"));
                        // Optionally, include 'needsGrooming' status if agent wants to log it or use it later
                        // summaryMap.put("needsGrooming", issue.get("needsGrooming"));
                        return summaryMap;
                    })
                    .collect(Collectors.toList());

            log.info("JiraTool: Found {} total tickets for project {}: {}", projectTickets.size(), projectKeyToFilter, projectTickets.stream().map(t -> t.get("id")).collect(Collectors.toList()));
            return objectMapper.writeValueAsString(projectTickets);
        } catch (IOException e) {
            log.error("Error in getTicketsForProject for project {}: {}", projectKeyToFilter, e.getMessage(), e);
            return "{\"error\": \"Could not get tickets for project " + projectKeyToFilter + ": " + e.getMessage() + "\"}";
        }
    }
}