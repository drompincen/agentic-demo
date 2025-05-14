package com.example.agency.handler; // Or a suitable package

import com.example.agency.agents.context.GroomingAgentContext;
import com.example.agency.util.AgentActivityLogger;
import com.example.agency.util.ToolIntent;
import com.example.scrum.tools.JiraTool;
import com.example.scrum.tools.MSTeamsTool;
import com.example.scrum.tools.OutlookTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class IntentHandler {
    private static final Logger log = LoggerFactory.getLogger(IntentHandler.class);

    private final JiraTool jiraTool;
    private final OutlookTool outlookTool;
    private final MSTeamsTool msTeamsTool;

    public IntentHandler(JiraTool jiraTool, OutlookTool outlookTool, MSTeamsTool msTeamsTool) {
        this.jiraTool = jiraTool;
        this.outlookTool = outlookTool;
        this.msTeamsTool = msTeamsTool;
    }

    /**
     * Executes the identified tool intent.
     * @param intent The ToolIntent to execute.
     * @param context The current context of the GroomingAgent.
     * @return A String result from the tool execution.
     */
    public String executeIntent(ToolIntent intent, GroomingAgentContext context) {
        String toolName = intent.toolName();
        Map<String, Object> arguments = intent.arguments();

        // Log before execution
        log.debug("IntentHandler: Executing tool: {} with arguments: {} (Context: {})",
                toolName, arguments, context.getOriginalTaskContext());

        try {
            switch (toolName) {
                // Jira Tools
                case "JIRA_getTicketsNeedingGrooming":
                    return jiraTool.getTicketsForProject((String) arguments.get("projectKey"));
                case "JIRA_getTicketDetails":
                    return jiraTool.getTicketDetails((String) arguments.get("ticketId"));
                case "JIRA_updateTicket":
                    return handleJiraUpdateTicket(arguments);
                case "JIRA_addComment":
                    return jiraTool.addComment(
                            (String) arguments.get("ticketId"),
                            (String) arguments.getOrDefault("author", context.getAgentName()), // Use agent name from context
                            (String) arguments.get("body")
                    );
                // Communication Tools
                case "OUTLOOK_sendEmail":
                    return handleOutlookSendEmail(arguments, context);
                case "MSTEAMS_sendMessage":
                    return handleMsTeamsSendMessage(arguments, context);
                case "MSTEAMS_readChannelHistory":
                    return msTeamsTool.readChannelHistory(
                            (String) arguments.get("channelName"),
                            (Integer) arguments.getOrDefault("messageCount", 5)
                    );
                default:
                    AgentActivityLogger.logError(context.getAgentName(), "IntentExecution", "Unknown tool requested: " + toolName, null);
                    return "{\"error\": \"Unknown tool: " + toolName + "\"}";
            }
        } catch (ClassCastException cce) {
            AgentActivityLogger.logError(context.getAgentName(), "IntentExecution - " + toolName, "Type casting error for arguments: " + arguments, cce);
            return "{\"error\": \"Invalid argument type for tool " + toolName + ". Details: " + cce.getMessage() + "\"}";
        } catch (Exception e) {
            AgentActivityLogger.logError(context.getAgentName(), "IntentExecution - " + toolName, "Unexpected error during tool execution with args " + arguments, e);
            return "{\"error\": \"Unexpected error during tool execution for " + toolName + ". Details: " + e.getMessage() + "\"}";
        }
    }

    private String handleJiraUpdateTicket(Map<String, Object> arguments) {
        Object value = arguments.get("value");
        String fieldName = (String) arguments.get("fieldName");
        String ticketId = (String) arguments.get("ticketId");

        if ("needsGrooming".equalsIgnoreCase(fieldName)) {
            if (value instanceof String) {
                value = Boolean.parseBoolean(((String) value).toLowerCase());
            } else if (!(value instanceof Boolean)) {
                log.warn("Invalid value type for needsGrooming: {} for ticket {}. Expected boolean or string 'true'/'false'.", value, ticketId);
                return "{\"error\": \"Invalid value type for needsGrooming. Expected boolean or string 'true'/'false'.\"}";
            }
        } else if ("storyPoints".equalsIgnoreCase(fieldName)) {
            if (value instanceof String) {
                try { value = Integer.parseInt((String)value); }
                catch (NumberFormatException e) { log.warn("Could not parse story points value '{}' to int for ticket {}.", value, ticketId); /* JiraTool might handle */ }
            } else if (value instanceof Number) {
                value = ((Number) value).intValue();
            } else {
                log.warn("Invalid value type for storyPoints: {} for ticket {}.", value, ticketId);
                return "{\"error\": \"Invalid value type for storyPoints. Expected number or string representation of a number.\"}";
            }
        }
        return jiraTool.updateTicket(ticketId, fieldName, value);
    }

    private String handleOutlookSendEmail(Map<String, Object> arguments, GroomingAgentContext context) {
        String to = (String) arguments.get("to");
        String subject = (String) arguments.get("subject");
        String body = (String) arguments.get("body");
        String ticketIdContext = context.getCurrentTicketIdForAnalysis();

        boolean isReportEmail = (subject != null && subject.toLowerCase().contains("grooming cycle summary")) ||
                context.getOriginalTaskContext().startsWith("ReportFor_");

        if (ticketIdContext != null && !isReportEmail &&
                !context.getTicketsNeedingFollowUpInCycle().stream().anyMatch(s->s.startsWith(ticketIdContext))) {
            context.getTicketsNeedingFollowUpInCycle().add(ticketIdContext + " (contacted via Outlook)");
        }
        return outlookTool.sendEmail(to, subject, body);
    }

    private String handleMsTeamsSendMessage(Map<String, Object> arguments, GroomingAgentContext context) {
        String channelOrUser = (String) arguments.get("channelOrUser");
        String messageText = (String) arguments.get("messageText");
        String ticketIdContext = context.getCurrentTicketIdForAnalysis();

        boolean isReportTeamsMessage = (messageText.toLowerCase().contains("grooming cycle for project") &&
                messageText.toLowerCase().contains("summary")) ||
                context.getOriginalTaskContext().startsWith("ReportFor_");

        if (ticketIdContext != null && !isReportTeamsMessage &&
                !context.getTicketsNeedingFollowUpInCycle().stream().anyMatch(s->s.startsWith(ticketIdContext))) {
            context.getTicketsNeedingFollowUpInCycle().add(ticketIdContext + " (contacted via MS Teams)");
        }
        return msTeamsTool.sendMessage(channelOrUser, messageText);
    }
}
