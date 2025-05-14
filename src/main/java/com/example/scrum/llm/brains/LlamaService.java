package com.example.scrum.llm.brains;

// Assuming Llama3.java and its inner classes are accessible
// (Llama, ChatFormat, ModelLoader, Sampler etc.)

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class LlamaService {
    // Make sure this path is correct for your system
    public static final String MODEL_PATH_CONFIG_KEY = "C:\\Users\\drom\\llama3\\Llama-3.2-1B-Instruct-Q8_0.gguf";

    private static final Logger log = LoggerFactory.getLogger(LlamaService.class);
    private final Llama model;
    private final ChatFormat chatFormat;

    public LlamaService(String modelPath) throws IOException {
        Path path = Path.of(modelPath);
        if (!Files.exists(path)) {
            throw new IOException("Model file not found at: " + modelPath);
        }
        // Using a common context length for loading, the model file itself will also have a max context.
        int desiredContextLength = 2048;
        this.model = ModelLoader.loadModel(path, desiredContextLength, true);
        log.info("Model loaded. Reported model.configuration().contextLength: {}", model.configuration().contextLength);
        this.chatFormat = new ChatFormat(this.model.tokenizer());
        log.info("LlamaService initialized with model: {} and effective context length: {}", modelPath, model.configuration().contextLength);
    }

    public String generateResponse(String systemPrompt, String userPrompt, int maxNewTokensToGenerate, float temperature) {
        // This is the version from our last successful iteration, which correctly calls Llama.generateTokens
        log.debug("Generating response with systemPrompt: [{}...], userPrompt: [{}...], maxNewTokensToGenerate: {}",
                (systemPrompt != null && !systemPrompt.isEmpty()) ? systemPrompt.substring(0, Math.min(systemPrompt.length(), 70)) : "null",
                (userPrompt != null && !userPrompt.isEmpty()) ? userPrompt.substring(0, Math.min(userPrompt.length(), 70)) : "null",
                maxNewTokensToGenerate);
        long startTime = System.nanoTime();

        if (userPrompt == null || userPrompt.isEmpty()) {
            log.warn("User prompt is empty or null. Returning empty string.");
            return "";
        }

        Llama.State state = model.createNewState(Llama3.BATCH_SIZE);

        List<Integer> formattedPromptTokens = new ArrayList<>();
        formattedPromptTokens.add(chatFormat.beginOfText);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            formattedPromptTokens.addAll(chatFormat.encodeMessage(new ChatFormat.Message(ChatFormat.Role.SYSTEM, systemPrompt)));
        }
        formattedPromptTokens.addAll(chatFormat.encodeMessage(new ChatFormat.Message(ChatFormat.Role.USER, userPrompt)));
        formattedPromptTokens.addAll(chatFormat.encodeHeader(new ChatFormat.Message(ChatFormat.Role.ASSISTANT, "")));

        int promptTokenCount = formattedPromptTokens.size();
        log.debug("Total tokens in formattedPromptTokens list (before Llama.generateTokens): {}", promptTokenCount);

        Sampler sampler = Llama3.selectSampler(
                model.configuration().vocabularySize,
                temperature, 0.95f, System.nanoTime());
        Set<Integer> stopTokens = chatFormat.getStopTokens();

        if (promptTokenCount >= model.configuration().contextLength - 1) {
            log.error("Formatted prompt ({} tokens) is too long for model context ({} tokens). Cannot generate.",
                    promptTokenCount, model.configuration().contextLength);
            return "[ERROR: Formatted prompt is too long for model context]";
        }

        int actualNewTokensPossibleInContext = model.configuration().contextLength - promptTokenCount - 1;
        int effectiveNewTokensToGenerate = Math.min(maxNewTokensToGenerate, actualNewTokensPossibleInContext);

        if (effectiveNewTokensToGenerate <= 0) {
            log.error("Not enough space in context to generate new tokens. Prompt tokens: {}, Model context: {}, Requested new: {}, Possible new in context: {}",
                    promptTokenCount, model.configuration().contextLength, maxNewTokensToGenerate, actualNewTokensPossibleInContext);
            return "[ERROR: No space left in context for new token generation]";
        }

        int maxPositionForLoop = promptTokenCount + effectiveNewTokensToGenerate;
        maxPositionForLoop = Math.min(maxPositionForLoop, model.configuration().contextLength -1);


        log.debug("Calling Llama.generateTokens with: promptTokenCount={}, effectiveNewTokensToGenerate={}, maxPositionForLoop (as maxTokens arg)={}",
                promptTokenCount, effectiveNewTokensToGenerate, maxPositionForLoop);

        List<Integer> responseTokens = Llama.generateTokens(
                model,
                state,
                0,
                formattedPromptTokens,
                stopTokens,
                maxPositionForLoop,
                sampler,
                false,
                null
        );

        if (!responseTokens.isEmpty() && stopTokens.contains(responseTokens.get(responseTokens.size() - 1))) {
            responseTokens.remove(responseTokens.size() - 1);
        }
        String responseText = model.tokenizer().decode(responseTokens);
        long endTime = System.nanoTime();
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        log.debug("LlamaService: Call duration: {} ms. Generated response snippet: [{}] ({} tokens)",
                durationMillis,
                responseText.substring(0, Math.min(responseText.length(), 100)) + (responseText.length() > 100 ? "..." : ""),
                responseTokens.size());
        return responseText.trim();
    }

    // --- Main method for testing different prompts ---
    public static void main(String[] args) {
        try {
            LlamaService llamaService = new LlamaService(MODEL_PATH_CONFIG_KEY);
            System.out.println("LlamaService initialized for prompt testing.");
            System.out.println("Model Context Length: " + llamaService.model.configuration().contextLength);
            System.out.println("-----------------------------------------------------\n");

            int maxNew = 70; // Default max new tokens for tests
            float temp = 0.1f; // Default temperature for tests (for directness)

            // Test Case 1: Very Short System Prompt, Simple User Command Request
            System.out.println("--- Test 1: Ultra-Concise System, Simple User Command ---");
            String sys1 = "Bot. Output command. No chat.";
            String user1 = "Task: List files. Command?";
            runTest(llamaService, "Test 1", sys1, user1, maxNew, temp);

            // Test Case 2: Simplified Agent System Prompt (from our last attempt)
            System.out.println("--- Test 2: Simplified Agent System Prompt ---");
            String sys2 = "You are JiraBot, an execution agent. Output ONLY the next single command. NO chatter. NO placeholders. Use actual values. " +
                    "Groomed ticket needs: Description, Assignee, Story Points > 0 (bugs can be 0). " +
                    "COMMANDS: " +
                    "\n- 'Get list for project PROJECT_KEY.' " +
                    "\n- 'Get details for TICKET_ID.' " +
                    "\n- 'Update TICKET_ID field FIELD_NAME to \"VALUE\"' " +
                    "\n- 'Add comment to TICKET_ID: \"TEXT\"' " +
                    "\n- 'Set needsGrooming for TICKET_ID to false.' (Ticket fully groomed) " +
                    "\n- 'Send email to EMAIL with subject \"SUBJ\" and body \"BODY\"' " +
                    "\n- 'Send msteams to channel CHANNEL: \"MSG\"' " +
                    "\n- 'Read last N from teams channel CHANNEL' " +
                    "\nIf clarification needed, use email/teams. " +
                    "If ticket details show it's groomed, command 'Set needsGrooming for TICKET_ID to false.' " +
                    "End of cycle report: I give summary, you command 'Send msteams to channel UPDATES_CHAN: \"SUMMARY_TEXT\"'. " +
                    "Next command?";
            String user2 = "Task: Start grooming cycle for project 'OMEGA'. My first step is to get the tickets needing grooming for this project. What is your first command for me to execute?";
            runTest(llamaService, "Test 2", sys2, user2, maxNew, temp);

            // Test Case 3: Test 2 System Prompt, but different user prompt (get details)
            System.out.println("--- Test 3: Simplified Agent System, Get Details User Prompt ---");
            String user3 = "Task: Groom ticket 'OMEGA-789'. My first step is to get the details for this ticket. What is your first command for me to execute?";
            runTest(llamaService, "Test 3", sys2, user3, maxNew, temp);

            // Test Case 4: Test 2 System Prompt, user prompt asking to use a placeholder (to see if it obeys NO PLACEHOLDERS)
            System.out.println("--- Test 4: Simplified Agent System, User Tempting Placeholder ---");
            String user4 = "My current ticket is 'OMEGA-789'. I want to update its description. What is the command using 'TICKET_ID' for the ticket and 'NEW_DESC' for the description?";
            runTest(llamaService, "Test 4", sys2, user4, maxNew, temp);

            // Test Case 5: Test 2 System Prompt, user prompt giving context and asking for update command
            System.out.println("--- Test 5: Simplified Agent System, User provides context for update ---");
            String user5 = "Context: Current ticket is 'OMEGA-123'. Its description is 'Old text'. " +
                    "Task: Update the description of 'OMEGA-123' to 'This is the brand new description'. " +
                    "What is your command?";
            runTest(llamaService, "Test 5", sys2, user5, maxNew, temp);

            // Test Case 6: Test 2 System Prompt, user prompt about a ticket being already groomed
            System.out.println("--- Test 6: Simplified Agent System, Ticket already groomed ---");
            String user6 = "I have retrieved details for ticket 'OMEGA-456'. It has a description, an assignee, and 5 story points. It seems fully groomed. " +
                    "What is your command?";
            runTest(llamaService, "Test 6", sys2, user6, maxNew, temp);

            // Test Case 7: Original VERY VERBOSE System Prompt (to compare token counts and performance)
            System.out.println("--- Test 7: Original Verbose System Prompt (from GroomingAgent) ---");
            String sysOriginalVerbose =
                    "You are a Jira Grooming Agent. I am your executor. Your ONLY task is to provide the next single, direct command for me to execute. " +
                            "YOU MUST NOT provide explanations, plans, alternatives, or conversational filler. " +
                            "YOU MUST ONLY output the command itself, and nothing else. " +
                            "YOU MUST NOT use placeholders like 'TICKET_ID', 'PROJECT_KEY', 'NEW_DESCRIPTION', 'ASSIGNEE_EMAIL', 'STORY_POINTS_NUMBER', or 'COMMENT_TEXT' in your commands; use actual values based on the context I provide. " +
                            "A well-groomed ticket MUST have: \n1. A clear Description. \n2. An Assignee. \n3. Story Points > 0 (unless a bug, then 0 is ok). \nConsider comments for context. "+
                            "\n\nVALID COMMANDS: " +
                            "\n--- Jira Tools ---" +
                            "\n- 'Get the list of tickets needing grooming for project PROJECT_KEY.' " +
                            "\n- 'Get details for ticket TICKET_ID.' " +
                            "\n- 'Update ticket TICKET_ID field description to \"NEW_DESCRIPTION.\"' " +
                            "\n- 'Update ticket TICKET_ID field assignee to \"ASSIGNEE_EMAIL\"' " +
                            "\n- 'Update ticket TICKET_ID field storyPoints to STORY_POINTS_NUMBER' " +
                            "\n- 'Add comment to ticket TICKET_ID: \"COMMENT_TEXT.\"' " +
                            "\n- 'Set needsGrooming for ticket TICKET_ID to false.' (Use this EXACTLY when a ticket is fully groomed). " +
                            "\n--- Communication Tools ---" +
                            "\n- 'Send email to RECIPIENT_EMAIL@example.com with subject \"SUBJECT_TEXT\" and body \"BODY_TEXT\"' " +
                            "\n- 'Send msteams message to channel CHANNEL_NAME: \"MESSAGE_TEXT\"' " +
                            "\n- 'Read last 5 messages from teams channel CHANNEL_NAME' " +
                            "\n\nIf a ticket requires clarification (e.g., missing assignee, unclear description), use a communication tool to ask the relevant person (e.g., reporter of the ticket). " +
                            "After sending a message, you might want to check for replies later using 'Read last N messages from teams channel CHANNEL_NAME' if appropriate, or wait for external updates. " +
                            "If, after getting details, a ticket already meets all grooming criteria, your command MUST be 'Set needsGrooming for ticket TICKET_ID to false.'"+
                            "\nAt the end of a grooming cycle, I will provide you a summary of groomed tickets and tickets needing follow-up. Your command should then be to send this summary, for example: " +
                            "\n- 'Send msteams message to channel project_alpha_updates: \"Grooming cycle for ALPHA complete. Groomed: [TICKET-1, TICKET-2]. Needs follow-up: [TICKET-3 (reason: needs assignee)].\"' " +
                            "\nWhat is your next command?";
            String user7 = "Task: Start grooming cycle for project 'DELTA'. My first step is to get the tickets needing grooming for this project. What is your first command for me to execute?";
            runTest(llamaService, "Test 7", sysOriginalVerbose, user7, maxNew, temp);


            System.out.println("\n--- Test 8: Simplified Agent System, End of Cycle Report Prompt ---");
            String user8 = "Grooming cycle for project OMEGA summary: Successfully groomed tickets: [OMEGA-123, OMEGA-456]. Tickets needing follow-up: [OMEGA-789 (needs assignee clarification)]. What is your command to send this summary report (e.g., via MS Teams to a relevant channel like 'project_omega_updates' or a scrum master email)?";
            runTest(llamaService, "Test 8", sys2, user8, 100, temp); // Give more tokens for report command

        } catch (IOException e) {
            log.error("Error initializing LlamaService in main: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error in LlamaService main", e);
        }
    }

    private static void runTest(LlamaService service, String testName, String systemPrompt, String userPrompt, int maxNewTokens, float temperature) {
        System.out.println("\n--- Running: " + testName + " ---");
        System.out.println("System Prompt (snippet): " + ((systemPrompt != null && !systemPrompt.isEmpty()) ? systemPrompt.substring(0, Math.min(systemPrompt.length(), 100)) + "..." : "None"));
        System.out.println("User Prompt: " + userPrompt);
        System.out.println("Max New Tokens: " + maxNewTokens + ", Temperature: " + temperature);
        long testStartTime = System.currentTimeMillis();
        String response = service.generateResponse(systemPrompt, userPrompt, maxNewTokens, temperature);
        long testEndTime = System.currentTimeMillis();
        System.out.println("LLM Raw Response:\n```\n" + response + "\n```");
        System.out.println("Test Duration: " + (testEndTime - testStartTime) + " ms");
        System.out.println("-----------------------------------------------------\n");
    }
}