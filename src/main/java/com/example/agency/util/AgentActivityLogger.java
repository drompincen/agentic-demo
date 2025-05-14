package com.example.agency.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

public class AgentActivityLogger {

    private static final Logger log = LoggerFactory.getLogger(AgentActivityLogger.class);
    private static final String PREFIX = "[AGENT_ACTIVITY] ";

    public static void logTaskReceived(String agentName, String rawTask) {
        log.info("{}{} received task: {}", PREFIX, agentName, rawTask);
    }

    public static void logToolExecution(String agentName, String taskContext, String toolName, Map<String, Object> arguments, String resultSummary) {
        String argsString = arguments.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        log.info("{}{} (Task: {}) USED TOOL: {} with args [{}] - Result: {}",
                PREFIX, agentName, taskContext, toolName, argsString, resultSummary);
    }

    public static void logAction(String agentName, String taskContext, String actionDescription) {
        log.info("{}{} (Task: {}) ACTION: {}", PREFIX, agentName, taskContext, actionDescription);
    }

    public static void logCycleStart(String agentName, String cycleType, String cycleIdentifier) {
        log.info("{}{} STARTED {} for: {}", PREFIX, agentName, cycleType, cycleIdentifier);
    }

    public static void logCycleComplete(String agentName, String cycleType, String cycleIdentifier, String summary) {
        log.info("{}{} COMPLETED {} for: {} - Summary: {}", PREFIX, agentName, cycleType, cycleIdentifier, summary);
    }

    public static void logError(String agentName, String taskContext, String errorMessage, Throwable t) {
        if (t != null) {
            log.error("{}{} (Task: {}) ERROR: {} - Exception: {}", PREFIX, agentName, taskContext, errorMessage, t.getMessage());
        } else {
            log.error("{}{} (Task: {}) ERROR: {}", PREFIX, agentName, taskContext, errorMessage);
        }
    }
    public static void logGroomingTicket(String agentName, String ticketId, String summary) {
        log.info("{}{} NOW GROOMING TICKET: {} (Summary: '{}')", PREFIX, agentName, ticketId, summary);
    }

    public static void logTicketGroomingComplete(String agentName, String ticketId, String status) {
        log.info("{}{} FINISHED GROOMING TICKET: {} - Status: {}", PREFIX, agentName, ticketId, status);
    }

    public static void logNoTicketsFound(String agentName, String projectKey) {
        log.info("{}{} No tickets found needing grooming for project: {}", PREFIX, agentName, projectKey);
    }
}