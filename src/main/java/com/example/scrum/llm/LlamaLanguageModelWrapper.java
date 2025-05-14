package com.example.scrum.llm;

import com.example.scrum.llm.brains.LlamaService;
import dev.langchain4j.model.language.LanguageModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class LlamaLanguageModelWrapper implements LanguageModel {
    private static final Logger log = LoggerFactory.getLogger(LlamaLanguageModelWrapper.class);
    protected final LlamaService llamaService;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 100; // Or make configurable
    private static final float DEFAULT_TEMPERATURE = 0.1f;   // Or make configurable

    public LlamaLanguageModelWrapper() throws IOException {
        // Assuming LlamaService.MODEL_PATH_CONFIG_KEY is accessible or read from a shared config
        this.llamaService = new LlamaService(LlamaService.MODEL_PATH_CONFIG_KEY);
    }

    /**
     * Standard LanguageModel interface method.
     * Assumes 'text' is primarily the user prompt.
     * System prompt is not explicitly handled here; LlamaService will use its default (null).
     */
    @Override
    public Response<String> generate(String text) {
        log.debug("LlamaLanguageModelWrapper.generate(String text) called. System prompt will be null.");
        return generate(null, text); // Call the richer internal method
    }

    /**
     * Custom method to allow specifying a system prompt.
     * This is what GroomingAgent should ideally call.
     */
    public Response<String> generate(String systemPrompt, String userPrompt) {
        return generate(systemPrompt, userPrompt, DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_TEMPERATURE);
    }

    public Response<String> generate(String systemPrompt, String userPrompt, int maxOutputTokens, float temperature) {
        if (userPrompt == null || userPrompt.isEmpty()) {
            log.warn("User prompt is null or empty, returning empty response.");
            return new Response<>("", new TokenUsage(0, 0), FinishReason.STOP);
        }

        String generatedText = llamaService.generateResponse(systemPrompt, userPrompt, maxOutputTokens, temperature);

        int inputTokensUser = userPrompt.split("\\s+").length; // Rough estimate
        int inputTokensSystem = (systemPrompt != null) ? systemPrompt.split("\\s+").length : 0; // Rough estimate
        int outputTokens = generatedText.split("\\s+").length; // Rough estimate
        TokenUsage tokenUsage = new TokenUsage(inputTokensUser + inputTokensSystem, outputTokens);

        return new Response<>(generatedText, tokenUsage, FinishReason.STOP);
    }
}