package com.connectai.agent.api;

import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.GroundedAnswerGenerator;
import com.connectai.agent.persistence.AgentConversation;
import com.connectai.agent.persistence.AgentConversationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Coordinates agent execution and persists its conversation history.
 */
@Service
@Slf4j
public class AgentChatService {

    private final AgentToolCatalog toolCatalog;
    private final ControlledAgentExecutor executor;
    private final GroundedAnswerGenerator answerGenerator;
    private final AgentConversationRepository conversationRepository;

    public AgentChatService(
            AgentToolCatalog toolCatalog,
            ControlledAgentExecutor executor,
            GroundedAnswerGenerator answerGenerator,
            AgentConversationRepository conversationRepository) {
        this.toolCatalog = toolCatalog;
        this.executor = executor;
        this.answerGenerator = answerGenerator;
        this.conversationRepository = conversationRepository;
    }

    public AgentChatResponse chat(String message) {
        String question = message == null ? "" : message.trim();

        if (question.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        if (question.length() > 2000) {
            throw new IllegalArgumentException(
                    "message must not exceed 2000 characters");
        }

        // Save first so the conversation has a persistent ID.
        AgentConversation conversation =
                conversationRepository.saveAndFlush(
                        new AgentConversation(question));

        try {
            log.info("Agent run started: conversationId={}, questionLength={}", conversation.getId(), question.length());
            var availableTools = toolCatalog.getAvailableTools();
            log.info("Agent catalog supplied to planner: conversationId={}, availableTools={}, toolIds={}",
                    conversation.getId(), availableTools.size(), availableTools.stream().map(t -> t.plannerId()).toList());
            var execution = executor.execute(question, availableTools);
            log.info(
                    "Agent execution completed: conversationId={}, status={}, steps={}",
                    conversation.getId(),
                    execution.status(),
                    execution.steps().size()
            );
           var answer = answerGenerator.generate(execution);

            // Persist the completed conversation.
            conversation.complete(execution, answer);
            conversationRepository.save(conversation);

            return AgentChatResponse.from(
                    conversation.getId(),
                    question,
                    execution,
                    answer);

        } catch (RuntimeException exception) {
            log.error("Agent run failed: conversationId={}, cause={}", conversation.getId(), exception.toString(), exception);
            // Record the failure before propagating the original exception.
            conversation.markFailed();
            conversationRepository.save(conversation);

            throw exception;
        }
    }
}
