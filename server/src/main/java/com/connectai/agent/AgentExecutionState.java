package com.connectai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable state accumulated during one dynamic agent execution.
 *
 * This model deliberately contains no MCP SDK or transport types.
 *
 * {@code stopReason} is a short, user-safe explanation of why the loop ended
 * (for example "planner chose FINISH" or "step budget exhausted"). It is null
 * while the execution is still running.
 */
public record AgentExecutionState(
        String question,
        List<AgentExecutionStep> steps,
        AgentExecutionStatus status,
        String stopReason
) {

    public AgentExecutionState {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(status, "status");

        if (question.isBlank()) {
            throw new IllegalArgumentException(
                    "question must not be blank");
        }

        steps = List.copyOf(steps);
    }

    public AgentExecutionState(
            String question,
            List<AgentExecutionStep> steps,
            AgentExecutionStatus status) {
        this(question, steps, status, null);
    }

    public static AgentExecutionState initial(String question) {
        return new AgentExecutionState(
                question,
                List.of(),
                AgentExecutionStatus.RUNNING,
                null);
    }

    public AgentExecutionState addStep(AgentExecutionStep step) {
        Objects.requireNonNull(step, "step");

        List<AgentExecutionStep> updated =
                new ArrayList<>(steps);
        updated.add(step);

        return new AgentExecutionState(
                question,
                updated,
                status,
                stopReason);
    }

    public AgentExecutionState withStatus(
            AgentExecutionStatus newStatus) {
        return new AgentExecutionState(
                question,
                steps,
                Objects.requireNonNull(newStatus, "newStatus"),
                stopReason);
    }

    /** Terminal transition with an explanation of why the loop stopped. */
    public AgentExecutionState finish(
            AgentExecutionStatus newStatus,
            String reason) {
        return new AgentExecutionState(
                question,
                steps,
                Objects.requireNonNull(newStatus, "newStatus"),
                reason);
    }

    public int stepCount() {
        return steps.size();
    }

    public boolean hasSuccessfulStep() {
        return steps.stream().anyMatch(step -> step.status() == AgentStepStatus.SUCCESS);
    }
}
