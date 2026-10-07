package com.connectai.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Application-level composition of the controlled agent loop and grounded answer generation.
 *
 * This is intentionally separate from AgentOrchestrator so the legacy investigation
 * flow does not become a monolith.
 */
@Service
@ConditionalOnBean(ControlledAgentExecutor.class)
public class GroundedAgentService {

    private final ControlledAgentExecutor executor;
    private final GroundedAnswerGenerator answerGenerator;

    public GroundedAgentService(
            ControlledAgentExecutor executor,
            GroundedAnswerGenerator answerGenerator) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.answerGenerator = Objects.requireNonNull(answerGenerator, "answerGenerator");
    }

    public GroundedAgentResult execute(
            String question,
            List<McpToolCandidate> availableTools) {
        AgentExecutionState execution =
                executor.execute(question, availableTools);

        return new GroundedAgentResult(
                execution,
                answerGenerator.generate(execution));
    }
}
