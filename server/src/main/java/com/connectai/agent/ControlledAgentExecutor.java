package com.connectai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Controls a bounded sequence of dynamic tool executions.
 *
 * Planning, single-tool execution and state storage remain separate concerns.
 * This class owns only the execution-loop policy.
 */
public class ControlledAgentExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(ControlledAgentExecutor.class);

    private final DynamicToolPlanner planner;
    private final PlannedToolExecutor toolExecutor;
    private final int maxSteps;

    public ControlledAgentExecutor(
            DynamicToolPlanner planner,
            PlannedToolExecutor toolExecutor,
            int maxSteps) {

        this.planner = Objects.requireNonNull(
                planner,
                "planner");

        this.toolExecutor = Objects.requireNonNull(
                toolExecutor,
                "toolExecutor");

        if (maxSteps <= 0) {
            throw new IllegalArgumentException(
                    "maxSteps must be greater than zero");
        }

        this.maxSteps = maxSteps;
    }

    /**
     * Executes at most {@code maxSteps} dynamic tool calls.
     *
     * The current execution state is supplied to the planner on every step,
     * allowing future planning decisions to use previous tool results.
     */
    public AgentExecutionState execute(
            String question,
            List<McpToolCandidate> availableTools) {

        AgentExecutionState state =
                AgentExecutionState.initial(question);

        for (int stepNumber = 1;
             stepNumber <= maxSteps;
             stepNumber++) {

            Optional<ToolSelectionPlan> plan =
                    planner.plan(
                            question,
                            availableTools,
                            state);

            log.debug(
                    "Tool planner evaluated investigation step {} (availableTools={}, previousSteps={})",
                    stepNumber,
                    availableTools == null ? 0 : availableTools.size(),
                    state.steps().size());

            if (plan.isEmpty()) {
                log.info("Tool planner selected no further tool at step {}; execution complete", stepNumber);
                return state.withStatus(
                        AgentExecutionStatus.COMPLETED);
            }

            AgentExecutionStep step =
                    toolExecutor.execute(
                            stepNumber,
                            plan.get());

            // Do not log tool arguments: they can contain user data or credentials.
            log.info(
                    "Tool selected: step={}, serverId={}, tool={}, reason={}",
                    stepNumber,
                    plan.get().serverId(),
                    plan.get().toolName(),
                    plan.get().reason());
            log.info(
                    "Tool execution result: step={}, tool={}, status={}",
                    stepNumber,
                    plan.get().toolName(),
                    step.status());

            state = state.addStep(step);

            if (step.status() == AgentStepStatus.FAILED) {
                return state.withStatus(
                        AgentExecutionStatus.FAILED);
            }
        }

        return state.withStatus(
                AgentExecutionStatus.STOPPED);
    }
}