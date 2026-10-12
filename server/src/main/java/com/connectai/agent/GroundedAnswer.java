package com.connectai.agent;

import java.util.List;
import java.util.Objects;

/**
 * Final answer plus the exact evidence Connect AI exposed to the answer generator.
 *
 * {@code coverage} says how far the evidence actually answers the question:
 * FULL - the evidence directly states the answer;
 * PARTIAL - related evidence, but the answer is incomplete or unverified;
 * NONE - the evidence does not answer the question.
 */
public record GroundedAnswer(
        String answer,
        List<GroundedEvidence> evidence,
        AgentExecutionStatus executionStatus,
        boolean grounded,
        boolean fallbackUsed,
        Coverage coverage
) {
    public enum Coverage { FULL, PARTIAL, NONE }

    public GroundedAnswer {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(executionStatus, "executionStatus");

        if (answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be blank");
        }
        evidence = List.copyOf(evidence);
        if (coverage == null) {
            coverage = !grounded || evidence.isEmpty() ? Coverage.NONE
                    : fallbackUsed ? Coverage.PARTIAL : Coverage.FULL;
        }
    }

    public GroundedAnswer(
            String answer,
            List<GroundedEvidence> evidence,
            AgentExecutionStatus executionStatus,
            boolean grounded,
            boolean fallbackUsed) {
        this(answer, evidence, executionStatus, grounded, fallbackUsed, null);
    }
}
