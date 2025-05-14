package com.example.agency.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaturalLanguageToolParser {
    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageToolParser.class);

    // Primary command for a well-groomed ticket
    private static final Pattern SET_NEEDS_GROOMING_FALSE_PATTERN = Pattern.compile(
            "set\\s+needsGrooming\\s+for\\s+(?:ticket\\s+)?['\"]?([A-Z0-9_]+-[0-9]+)['\"]?\\s+to\\s+false", Pattern.CASE_INSENSITIVE);

    // LLM might still try to update explicitly
    private static final Pattern UPDATE_TICKET_FIELD_PATTERN = Pattern.compile(
            "update\\s+(?:ticket\\s+)?['\"]?([A-Z0-9_]+-[0-9]+)['\"]?\\s+(?:(?:set\\s+)?field\\s+)?['\"]?(\\w+)['\"]?\\s+to\\s+['\"]?([^'\"]+)['\"]?", Pattern.CASE_INSENSITIVE);

    private static final Pattern ADD_COMMENT_PATTERN = Pattern.compile(
            "add\\s+comment\\s+to\\s+(?:ticket\\s+)?['\"]?([A-Z0-9_]+-[0-9]+)['\"]?\\s*:\\s*['\"](.+)['\"]", Pattern.CASE_INSENSITIVE);

    // For sending the final report OR if LLM decides to contact for clarification
    private static final Pattern SEND_MSTEAMS_MESSAGE_PATTERN = Pattern.compile(
            "send\\s+(?:teams?|msteams)\\s+message\\s+to\\s+(?:channel\\s+|user\\s+)?['\"]?([^\\s'\"]+)['\"]?\\s*:\\s*['\"](.+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEND_EMAIL_PATTERN = Pattern.compile(
            "send\\s+(?:an\\s+)?email\\s+to\\s+['\"]?([^\\s'\"]+@[^\\s'\"]+)['\"]?\\s+with\\s+subject\\s+['\"]([^'\"]+)['\"]\\s+(?:and\\s+|with\\s+)?body\\s+['\"](.+)['\"]", Pattern.CASE_INSENSITIVE);


    public static ToolIntent parse(String llmResponse) {
        Map<String, Object> args = new HashMap<>();
        Matcher matcher;

        matcher = SET_NEEDS_GROOMING_FALSE_PATTERN.matcher(llmResponse);
        if (matcher.find()) {
            args.put("ticketId", matcher.group(1).toUpperCase());
            args.put("fieldName", "needsGrooming");
            args.put("value", "false");
            log.debug("Parsed JIRA_updateTicket (Set needsGrooming to false) with args: {}", args);
            return new ToolIntent("JIRA_updateTicket", args);
        }

        matcher = UPDATE_TICKET_FIELD_PATTERN.matcher(llmResponse);
        if (matcher.find()) {
            String fieldName = matcher.group(2);
            // Specifically handle if LLM tries to set needsGrooming to true via general update
            if ("needsGrooming".equalsIgnoreCase(fieldName) && "true".equalsIgnoreCase(matcher.group(3))) {
                args.put("ticketId", matcher.group(1).toUpperCase());
                args.put("fieldName", "needsGrooming");
                args.put("value", "true");
                log.debug("Parsed JIRA_updateTicket (Set needsGrooming to true via update) with args: {}", args);
                return new ToolIntent("JIRA_updateTicket", args);
            }
            // General field update
            args.put("ticketId", matcher.group(1).toUpperCase());
            args.put("fieldName", fieldName);
            args.put("value", matcher.group(3));
            log.debug("Parsed JIRA_updateTicket (general field) with args: {}", args);
            return new ToolIntent("JIRA_updateTicket", args);
        }

        matcher = ADD_COMMENT_PATTERN.matcher(llmResponse);
        if (matcher.find()) {
            args.put("ticketId", matcher.group(1).toUpperCase());
            args.put("body", matcher.group(2));
            log.debug("Parsed JIRA_addComment with args: {}", args);
            return new ToolIntent("JIRA_addComment", args);
        }

        matcher = SEND_MSTEAMS_MESSAGE_PATTERN.matcher(llmResponse);
        if (matcher.find()) {
            args.put("channelOrUser", matcher.group(1));
            args.put("messageText", matcher.group(2));
            log.debug("Parsed MSTEAMS_sendMessage with args: {}", args);
            return new ToolIntent("MSTEAMS_sendMessage", args);
        }

        matcher = SEND_EMAIL_PATTERN.matcher(llmResponse);
        if (matcher.find()) {
            args.put("to", matcher.group(1));
            args.put("subject", matcher.group(2));
            args.put("body", matcher.group(3));
            log.debug("Parsed OUTLOOK_sendEmail with args: {}", args);
            return new ToolIntent("OUTLOOK_sendEmail", args);
        }

        // Removed Get list, Get details, Read history as they are less likely to be LLM outputs now
        // or agent handles them directly.

        log.debug("No specific tool intent recognized by parser in [{}]. Assuming natural language analysis.", llmResponse);
        return new ToolIntent(null, Collections.emptyMap());
    }
}