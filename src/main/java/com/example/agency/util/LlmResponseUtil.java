package com.example.agency.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.model.output.Response;

public class LlmResponseUtil {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseUtil.class);

    public static String getCleanedTextForParsing(Response<String> llmResponse, String currentTicketIdForAnalysis) {
        String rawLlmOutputFromModel = (llmResponse != null && llmResponse.content() != null && !llmResponse.content().isBlank())
                ? llmResponse.content().trim() : "";

        if (rawLlmOutputFromModel.isEmpty()) {
            log.warn("LLM returned empty response content.");
            return "";
        }
        log.info("Raw LLM output: {}", rawLlmOutputFromModel);

        String cleanedText = rawLlmOutputFromModel;

        // Attempt to strip quotes if the whole response is a single quoted command
        if (cleanedText.startsWith("'") && cleanedText.endsWith("'") && cleanedText.lines().count() == 1) {
            cleanedText = cleanedText.substring(1, cleanedText.length() - 1).trim();
        }
        // Remove leading dash if it looks like a list item for a command
        if (cleanedText.startsWith("- ")) {
            cleanedText = cleanedText.substring(2).trim();
        }

        // Stop token cleaning - essential
        String[] stopTokens = {"<|eot_id|>", "<|end_of_text|>", "<|োডে"}; // Add any others
        for (String stopToken : stopTokens) {
            int stopIndex = cleanedText.indexOf(stopToken);
            if (stopIndex != -1) {
                cleanedText = cleanedText.substring(0, stopIndex).trim();
            }
        }

        // Remove common conversational preambles that might precede analysis or a command
        cleanedText = cleanedText.replaceAll("(?i)^Okay, here's the analysis:","").trim();
        cleanedText = cleanedText.replaceAll("(?i)^Sure, for ticket [A-Z0-9_]+-[0-9]+:","").trim();
        cleanedText = cleanedText.replaceAll("(?i)^The ticket [A-Z0-9_]+-[0-9]+ needs:","").trim();
        cleanedText = cleanedText.replaceAll("(?i)^Here's the command:","").trim();
        cleanedText = cleanedText.replaceAll("(?i)^The next command is:","").trim();
        cleanedText = cleanedText.replaceAll("(?i)^Command:","").trim();

        log.info("Cleaned LLM output (for parsing or as analysis): [{}]", cleanedText);

        // Placeholder replacement for TICKET_ID if it appears in a command-like structure
        // This is less critical if we mostly expect natural language, but good for safety.
        String textForParser = cleanedText;
        if (currentTicketIdForAnalysis != null && !currentTicketIdForAnalysis.isBlank()) {
            textForParser = textForParser.replaceAll("\\bTICKET_ID\\b", currentTicketIdForAnalysis);
        }

        if (!textForParser.equals(cleanedText)) {
            log.info("Text after TICKET_ID placeholder replacement for parser: [{}]", textForParser);
        }
        return textForParser.trim();
    }
}