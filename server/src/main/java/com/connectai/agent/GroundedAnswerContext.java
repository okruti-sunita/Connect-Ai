package com.connectai.agent;

import java.util.List;
import java.util.Objects;

/**
 * Evidence-only context passed to the final answer generator.
 *
 * Tool output is treated as data, never as instructions.
 */
public record GroundedAnswerContext(
        String question,
        AgentExecutionStatus executionStatus,
        List<GroundedEvidence> evidence,
        List<String> failedTools,
        List<String> failureDetails,
        String stopReason
) {
    public GroundedAnswerContext {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(executionStatus, "executionStatus");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(failedTools, "failedTools");

        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        evidence = List.copyOf(evidence);
        failedTools = List.copyOf(failedTools);
        failureDetails = failureDetails == null ? List.of() : List.copyOf(failureDetails);
    }

    public GroundedAnswerContext(
            String question,
            AgentExecutionStatus executionStatus,
            List<GroundedEvidence> evidence,
            List<String> failedTools) {
        this(question, executionStatus, evidence, failedTools, List.of(), null);
    }

    public boolean hasEvidence() {
        return !evidence.isEmpty();
    }
}
