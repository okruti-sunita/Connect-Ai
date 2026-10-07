package com.connectai.agent;

import java.util.Objects;

/**
 * Immutable record of one planned tool execution.
 */
public record AgentExecutionStep(
        int stepNumber,
        ToolSelectionPlan plan,
        ToolExecutionResult result,
        AgentStepStatus status
) {

    public AgentExecutionStep {
        if (stepNumber <= 0) {
            throw new IllegalArgumentException(
                    "stepNumber must be greater than zero");
        }

        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(status, "status");
    }
}
