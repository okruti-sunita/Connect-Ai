package com.connectai.agent;

/** Generates the final answer from an execution state's collected evidence. */
public interface GroundedAnswerGenerator {
    GroundedAnswer generate(AgentExecutionState state);
}
