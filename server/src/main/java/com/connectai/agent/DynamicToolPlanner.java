package com.connectai.agent;

import java.util.List;
import java.util.Optional;

/**
 * Application-level abstraction for selecting a dynamic tool.
 *
 * Implementations may use an LLM, rules, or another planning strategy.
 */
public interface DynamicToolPlanner {

    /**
     * Select the next MCP tool for the question.
     *
     * Empty means that the planner believes no tool should be called.
     */
    Optional<ToolSelectionPlan> plan(
            String question,
            List<McpToolCandidate> availableTools);

    /**
     * Plans the next step with visibility into the execution accumulated so far.
     *
     * The default implementation preserves the Task 6 contract for simple
     * planners. Stateful planners should override this method.
     */
    default Optional<ToolSelectionPlan> plan(
            String question,
            List<McpToolCandidate> availableTools,
            AgentExecutionState state) {
        return plan(question, availableTools);
    }
}