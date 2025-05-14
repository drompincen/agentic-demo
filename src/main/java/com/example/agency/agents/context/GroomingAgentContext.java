package com.example.agency.agents.context;


import java.util.List;
import java.util.Map;

// This is a simple example; expand as needed
public class GroomingAgentContext {
    private final String agentName;
    private final String originalTaskContext; // e.g., "GroomingCycleFor_ALPHA" or "GroomTicket_ID"
    private final String currentTicketIdForAnalysis;
    private final String currentReportingProjectKey;
    private final List<String> successfullyGroomedTicketsInCycle;
    private final List<String> ticketsNeedingFollowUpInCycle;
    private final List<Map<String, Object>> pendingTicketsToGroom; // If handler needs to modify it

    public GroomingAgentContext(String agentName, String originalTaskContext, String currentTicketIdForAnalysis,
                                String currentReportingProjectKey, List<String> successfullyGroomedTicketsInCycle,
                                List<String> ticketsNeedingFollowUpInCycle, List<Map<String, Object>> pendingTicketsToGroom) {
        this.agentName = agentName;
        this.originalTaskContext = originalTaskContext;
        this.currentTicketIdForAnalysis = currentTicketIdForAnalysis;
        this.currentReportingProjectKey = currentReportingProjectKey;
        this.successfullyGroomedTicketsInCycle = successfullyGroomedTicketsInCycle;
        this.ticketsNeedingFollowUpInCycle = ticketsNeedingFollowUpInCycle;
        this.pendingTicketsToGroom = pendingTicketsToGroom;
    }

    // Getters
    public String getAgentName() { return agentName; }
    public String getOriginalTaskContext() { return originalTaskContext; }
    public String getCurrentTicketIdForAnalysis() { return currentTicketIdForAnalysis; }
    public String getCurrentReportingProjectKey() { return currentReportingProjectKey; }
    public List<String> getSuccessfullyGroomedTicketsInCycle() { return successfullyGroomedTicketsInCycle; }
    public List<String> getTicketsNeedingFollowUpInCycle() { return ticketsNeedingFollowUpInCycle; }
    public List<Map<String, Object>> getPendingTicketsToGroom() { return pendingTicketsToGroom; }

    // Potentially add setters or methods to modify lists if handlers need to directly update them,
    // though it might be cleaner for handlers to return data that the agent then uses to update its state.
}