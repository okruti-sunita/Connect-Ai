package com.connectai.agent;

import java.util.Objects;

/**
 * Immutable record of one planned tool execution.
 *
 * {@code errorMessage} is a short, redacted explanation when the step failed
 * (transport failure, tool-level error or rejected arguments); null otherwise.
 */
public record AgentExecutionStep(
        int stepNumber,
        ToolSelectionPlan plan,
        ToolExecutionResult result,
        AgentStepStatus status,
        String errorMessage,
        long durationMs
) {

    public AgentExecutionStep {
        if (stepNumber <= 0) {
            throw new IllegalArgumentException(
                    "stepNumber must be greater than zero");
        }

        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(status, "status");

        if (durationMs < 0) {
            durationMs = 0;
        }
    }

    public AgentExecutionStep(
            int stepNumber,
            ToolSelectionPlan plan,
            ToolExecutionResult result,
            AgentStepStatus status) {
        this(stepNumber, plan, result, status, null, 0);
    }
}
