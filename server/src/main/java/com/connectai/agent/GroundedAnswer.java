package com.connectai.agent;

import java.util.List;
import java.util.Objects;

/**
 * Final answer plus the exact evidence Connect AI exposed to the answer generator.
 */
public record GroundedAnswer(
        String answer,
        List<GroundedEvidence> evidence,
        AgentExecutionStatus executionStatus,
        boolean grounded,
        boolean fallbackUsed
) {
    public GroundedAnswer {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(executionStatus, "executionStatus");

        if (answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be blank");
        }
        evidence = List.copyOf(evidence);
    }
}
