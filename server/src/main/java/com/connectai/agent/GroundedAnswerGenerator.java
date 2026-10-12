package com.connectai.agent;

import java.util.List;

/** Generates the final answer from an execution state's collected evidence. */
public interface GroundedAnswerGenerator {
    GroundedAnswer generate(AgentExecutionState state);

    /**
     * Same, knowing which kinds of data the question asks for that no connected tool provides
     * (for example "failures" when no CI tool is connected). Such an answer can be at most PARTIAL.
     */
    default GroundedAnswer generate(AgentExecutionState state, List<String> uncoveredCapabilities) {
        return generate(state, new AnswerHints(uncoveredCapabilities, null));
    }

    /** Same, with the full run context (uncovered data, connections that were not queried). */
    default GroundedAnswer generate(AgentExecutionState state, AnswerHints hints) {
        return generate(state);
    }
}
