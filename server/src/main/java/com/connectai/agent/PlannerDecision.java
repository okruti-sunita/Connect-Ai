package com.connectai.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * One planner decision: either run a validated tool plan, or finish with a reason.
 */
public record PlannerDecision(ToolSelectionPlan plan, String finishReason) {

    public static PlannerDecision run(ToolSelectionPlan plan) {
        return new PlannerDecision(Objects.requireNonNull(plan, "plan"), null);
    }

    public static PlannerDecision finish(String reason) {
        return new PlannerDecision(null, reason == null || reason.isBlank() ? "planner chose FINISH" : reason);
    }

    public boolean isFinish() {
        return plan == null;
    }

    public Optional<ToolSelectionPlan> planOptional() {
        return Optional.ofNullable(plan);
    }
}
