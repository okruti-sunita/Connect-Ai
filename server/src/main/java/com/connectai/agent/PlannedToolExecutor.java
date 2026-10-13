package com.connectai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Executes exactly one already-selected tool plan.
 *
 * This class deliberately does not perform planning and does not know about
 * MCP SDK or transport details. It converts one plan into one execution step and
 * distinguishes three outcomes:
 *
 * <ul>
 *     <li>success                        -> SUCCESS (an empty result is still a success)</li>
 *     <li>tool-level error (isError)     -> FAILED, the tool's own error text kept</li>
 *     <li>transport / validation failure -> FAILED, a short exception message kept</li>
 * </ul>
 */
public class PlannedToolExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(PlannedToolExecutor.class);

    static final int MAX_ERROR_CHARS = 500;

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
        long started = System.nanoTime();

        try {
            ToolExecutionResult result =
                    toolExecutionPort.execute(plan.toExecutionRequest());

            if (result == null) {
                throw new IllegalStateException(
                        "tool execution returned null result");
            }

            if (result.error()) {
                return new AgentExecutionStep(
                        stepNumber,
                        plan,
                        result,
                        AgentStepStatus.FAILED,
                        "Tool reported an error: " + truncate(firstText(result)),
                        elapsedMs(started));
            }

            return new AgentExecutionStep(
                    stepNumber,
                    plan,
                    result,
                    AgentStepStatus.SUCCESS,
                    null,
                    elapsedMs(started));
        } catch (RuntimeException exception) {
            String message = describe(exception);
            log.warn(
                    "Tool execution failed for {} at step {}: {}",
                    plan.toolName(),
                    stepNumber,
                    message);

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
                    AgentStepStatus.FAILED,
                    message,
                    elapsedMs(started));
        }
    }

    private static String firstText(ToolExecutionResult result) {
        return result.content().stream()
                .map(ToolExecutionContent::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("(no error details returned)");
    }

    /** Uses the innermost meaningful message: HTTP wrappers often hide the useful cause. */
    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                && (message == null || !message.contains(cause.getMessage()))) {
            message = (message == null ? "" : message + ": ") + cause.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return SafeDiagnostic.sanitize(message, MAX_ERROR_CHARS);
    }

    private static String truncate(String value) {
        return SafeDiagnostic.sanitize(value, MAX_ERROR_CHARS);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
