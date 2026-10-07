package com.connectai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Executes exactly one already-selected tool plan.
 *
 * This class deliberately does not perform planning and does not know about
 * MCP SDK or transport details. It converts one plan into one execution step.
 */
public class PlannedToolExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(PlannedToolExecutor.class);

    private final ToolExecutionPort toolExecutionPort;

    public PlannedToolExecutor(ToolExecutionPort toolExecutionPort) {
        this.toolExecutionPort = Objects.requireNonNull(
                toolExecutionPort,
                "toolExecutionPort");
    }

    public AgentExecutionStep execute(
            int stepNumber,
            ToolSelectionPlan plan) {

        if (stepNumber <= 0) {
            throw new IllegalArgumentException(
                    "stepNumber must be greater than zero");
        }

        Objects.requireNonNull(plan, "plan");

        try {
            ToolExecutionResult result =
                    toolExecutionPort.execute(plan.toExecutionRequest());

            if (result == null) {
                throw new IllegalStateException(
                        "tool execution returned null result");
            }

            AgentStepStatus status = result.error()
                    ? AgentStepStatus.FAILED
                    : AgentStepStatus.SUCCESS;

            return new AgentExecutionStep(
                    stepNumber,
                    plan,
                    result,
                    status);
        } catch (RuntimeException exception) {
            log.warn(
                    "Tool execution failed for {} at step {}: {}",
                    plan.toolName(),
                    stepNumber,
                    exception.getMessage());

            ToolExecutionResult failure =
                    new ToolExecutionResult(
                            plan.toolName(),
                            true,
                            List.of(),
                            null);

            return new AgentExecutionStep(
                    stepNumber,
                    plan,
                    failure,
                    AgentStepStatus.FAILED);
        }
    }
}
