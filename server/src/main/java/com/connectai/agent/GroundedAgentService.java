package com.connectai.agent;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Application service that combines controlled agent execution with grounded
 * final-answer generation. The execution and answer-generation responsibilities
 * remain separate so the agent flow does not become a monolith.
 */
@Service
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
