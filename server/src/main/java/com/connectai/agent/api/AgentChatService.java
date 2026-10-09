package com.connectai.agent.api;

import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.GroundedAnswerGenerator;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Application service coordinating tool discovery, controlled execution and grounded response generation. */
@Service
public class AgentChatService {
    private final AgentToolCatalog toolCatalog;
    private final ControlledAgentExecutor executor;
    private final GroundedAnswerGenerator answerGenerator;

    public AgentChatService(AgentToolCatalog toolCatalog, ControlledAgentExecutor executor, GroundedAnswerGenerator answerGenerator) {
        this.toolCatalog = toolCatalog;
        this.executor = executor;
        this.answerGenerator = answerGenerator;
    }

    public AgentChatResponse chat(String message) {
        String question = message == null ? "" : message.trim();
        if (question.isBlank()) throw new IllegalArgumentException("message must not be blank");
        var availableTools = toolCatalog.getAvailableTools();
        var execution = executor.execute(question, availableTools);
        var answer = answerGenerator.generate(execution);
        return AgentChatResponse.from(UUID.randomUUID(), question, execution, answer);
    }
}
