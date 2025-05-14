package com.example.agency;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.language.LanguageModel;
import dev.langchain4j.model.output.Response;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Base class for all agents using a LanguageModel from LangChain4j.
 */
public abstract class BaseLlmAgent {
    private static final Logger log = LoggerFactory.getLogger(BaseLlmAgent.class);
    protected final LanguageModel llm;
    protected final ChatMemory memory;
    private final ObjectMapper json = new ObjectMapper();

    protected BaseLlmAgent(LanguageModel llm, ChatMemory memory) {
        this.llm = llm;
        this.memory = memory;
    }

    /**
     * Send a system prompt and a user prompt to the model, returning parsed tasks.
     */
    protected List<String> generateTasks(String systemPrompt, String userPrompt) {
        // record system and user messages
        memory.add(new SystemMessage(systemPrompt));
        memory.add(new UserMessage(userPrompt));

        // generate response
        Response<String> response = llm.generate(userPrompt);
        String text;
        if (response == null || response.content() == null || response.content().isBlank()) {
            log.warn("Empty response from LLM");
            return Collections.emptyList();
        } else {
            text = response.content();
        }
        // record assistant message
        memory.add(new AiMessage(text));

        return parseTasks(text);
    }

    /**
     * Parse JSON array or wrapped object into List<String>.
     */
    protected List<String> parseTasks(String resp) {
        if (resp == null || resp.isBlank()) {
            log.warn("Empty LLM response");
            return Collections.emptyList();
        }
        try {
            return json.readValue(resp, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.info("Not a JSON array; attempting fallback");
        }
        try {
            JsonNode root = json.readTree(resp);
            if (root.isObject() && root.has("response")) {
                JsonNode inner = root.get("response");
                if (inner.isArray()) {
                    return json.convertValue(inner, new TypeReference<List<String>>() {});
                } else if (inner.isTextual()) {
                    return List.of(inner.asText());
                }
            }
        } catch (Exception e) {
            log.error("Fallback parsing failed", e);
        }
        return Collections.emptyList();
    }
}
