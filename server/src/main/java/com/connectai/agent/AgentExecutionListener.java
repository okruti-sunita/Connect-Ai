package com.connectai.agent;

/**
 * Receives progress while an execution runs, so callers can persist each step as soon
 * as it finishes instead of only at the end. Implementations must not throw.
 */
@FunctionalInterface
public interface AgentExecutionListener {

    AgentExecutionListener NONE = step -> { };

    void onStep(AgentExecutionStep step);
}
