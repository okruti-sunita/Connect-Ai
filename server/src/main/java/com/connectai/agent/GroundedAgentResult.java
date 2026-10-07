package com.connectai.agent;

import java.util.Objects;

/** Complete result of the dynamic execution plus grounded final response. */
public record GroundedAgentResult(
        AgentExecutionState execution,
        GroundedAnswer answer
) {
    public GroundedAgentResult {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(answer, "answer");
    }
}
