package com.connectai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable state accumulated during one dynamic agent execution.
 *
 * This model deliberately contains no MCP SDK or transport types.
 */
public record AgentExecutionState(
        String question,
        List<AgentExecutionStep> steps,
        AgentExecutionStatus status
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

    public static AgentExecutionState initial(String question) {
        return new AgentExecutionState(
                question,
                List.of(),
                AgentExecutionStatus.RUNNING);
    }

    public AgentExecutionState addStep(AgentExecutionStep step) {
        Objects.requireNonNull(step, "step");

        List<AgentExecutionStep> updated =
                new ArrayList<>(steps);
        updated.add(step);

        return new AgentExecutionState(
                question,
                updated,
                status);
    }

    public AgentExecutionState withStatus(
            AgentExecutionStatus newStatus) {
        return new AgentExecutionState(
                question,
                steps,
                Objects.requireNonNull(newStatus, "newStatus"));
    }

    public int stepCount() {
        return steps.size();
    }
}
