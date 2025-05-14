package com.example.agency.driver;

import com.example.agency.agents.GroomingAgent;
import com.example.agency.agents.TechAgent;
import com.example.agency.agents.UserAgent;
import com.example.agency.orchestrator.SchedulerAgent;
import com.example.agency.bus.Source.FileSource;
import com.example.scrum.tools.JiraTool;
import com.example.scrum.tools.MSTeamsTool;
import com.example.scrum.tools.OutlookTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.language.LanguageModel;
import com.example.scrum.llm.LlamaLanguageModelWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;

public class AgencyApp {
    private static final Logger log = LoggerFactory.getLogger(AgencyApp.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting AgencyApp...");
        FileSource eventBus = new FileSource("events");
        log.info("Event bus initialized for topic 'events'.");

        LanguageModel llm = new LlamaLanguageModelWrapper();
        log.info("LanguageModel (LlamaLanguageModelWrapper) initialized.");

        JiraTool jiraTool = new JiraTool();
        log.info("JiraTool initialized.");
        OutlookTool outlookTool = new OutlookTool();
        log.info("OutlookTool initialized.");
        MSTeamsTool msTeamsTool = new MSTeamsTool(); // Shared instance or each agent can have its own
        log.info("MSTeamsTool initialized.");

        new SchedulerAgent(null, null, eventBus);
        log.info("SchedulerAgent started.");

//        ChatMemory groomingMemory = MessageWindowChatMemory.withMaxMessages(50);
//        new GroomingAgent(llm, groomingMemory, jiraTool, outlookTool, msTeamsTool);
//        log.info("GroomingAgent started.");

        ChatMemory userAgentMemory = MessageWindowChatMemory.withMaxMessages(15);
        // Pass msTeamsTool to UserAgent
        new UserAgent(llm, userAgentMemory, eventBus, msTeamsTool);
        log.info("UserAgent started.");

        ChatMemory techAgentMemory = MessageWindowChatMemory.withMaxMessages(15);
        // Pass msTeamsTool to TechAgent
        new TechAgent(llm, techAgentMemory, eventBus, msTeamsTool);
        log.info("TechAgent started.");

        try {
            log.info("Pausing briefly before publishing initial event...");
            Thread.sleep(1000);
        } catch (InterruptedException e) { /* ... */ }

        // Trigger the UserAgent/TechAgent flow via SchedulerAgent's TodayEvent logic
        String initialEventPayload = "TodayEvent:" + Instant.now();
        // Or, to directly start the user-tech conversation without other TodayEvent actions:
        // String initialEventPayload = "StartUserTechSupportConversation:" + Instant.now();
        // (You'd need SchedulerAgent to handle "StartUserTechSupportConversation" and enqueue for UserAgent)
        // For now, TodayEvent should work if userTechChatInitiatedThisRun is false in SchedulerAgent.

        eventBus.publish(initialEventPayload);
        log.info("Published initial event: {}", initialEventPayload);

        log.info("AgencyApp started successfully. Main thread will wait.");
    }
}