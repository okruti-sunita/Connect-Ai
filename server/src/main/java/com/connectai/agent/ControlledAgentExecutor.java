package com.connectai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Controls a bounded sequence of dynamic tool executions.
 *
 * Planning, single-tool execution and state storage remain separate concerns.
 * This class owns only the execution-loop policy and its stop conditions:
 *
 * <ul>
 *     <li>no tools available                      -> FAILED</li>
 *     <li>planner chooses FINISH                  -> COMPLETED</li>
 *     <li>planner error (after its own retries)   -> FAILED, earlier steps kept</li>
 *     <li>identical call repeated (no progress)   -> STOPPED</li>
 *     <li>too many consecutive tool failures      -> FAILED</li>
 *     <li>step or time budget exhausted           -> STOPPED</li>
 * </ul>
 *
 * A single failed tool call does not end the run: the planner sees the failure and may
 * recover with a different tool, bounded by {@code maxConsecutiveFailures}.
 */
public class ControlledAgentExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(ControlledAgentExecutor.class);

    private final DynamicToolPlanner planner;
    private final PlannedToolExecutor toolExecutor;
    private final int maxSteps;
    private final Duration maxDuration;
    private final int maxConsecutiveFailures;

    public ControlledAgentExecutor(
            DynamicToolPlanner planner,
            PlannedToolExecutor toolExecutor,
            int maxSteps) {
        this(planner, toolExecutor, maxSteps, Duration.ofMinutes(10), 2);
    }

    public ControlledAgentExecutor(
            DynamicToolPlanner planner,
            PlannedToolExecutor toolExecutor,
            int maxSteps,
            Duration maxDuration,
            int maxConsecutiveFailures) {

        this.planner = Objects.requireNonNull(planner, "planner");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");

        if (maxSteps <= 0) {
            throw new IllegalArgumentException(
                    "maxSteps must be greater than zero");
        }
        if (maxConsecutiveFailures <= 0) {
            throw new IllegalArgumentException(
                    "maxConsecutiveFailures must be greater than zero");
        }

        this.maxSteps = maxSteps;
        this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    public AgentExecutionState execute(
            String question,
            List<McpToolCandidate> availableTools) {
        return execute(question, availableTools, AgentExecutionListener.NONE);
    }

    /**
     * Executes at most {@code maxSteps} dynamic tool calls.
     *
     * The current execution state is supplied to the planner on every step,
     * allowing planning decisions to use previous tool results.
     */
    public AgentExecutionState execute(
            String question,
            List<McpToolCandidate> availableTools,
            AgentExecutionListener listener) {

        AgentExecutionListener progress = listener == null ? AgentExecutionListener.NONE : listener;
        AgentExecutionState state = AgentExecutionState.initial(question);

        if (availableTools == null || availableTools.isEmpty()) {
            log.warn("Agent run has no connected MCP tools; nothing can be executed");
            return state.finish(AgentExecutionStatus.FAILED,
                    "No connected MCP tools are available. Connect an MCP server on the Connections page and try again.");
        }

        long startedAt = System.nanoTime();
        Set<String> executedCalls = new HashSet<>();
        int consecutiveFailures = 0;

        for (int stepNumber = 1; stepNumber <= maxSteps; stepNumber++) {

            if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(maxDuration) > 0) {
                log.warn("Agent run exceeded its time budget of {}s at step {}", maxDuration.toSeconds(), stepNumber);
                return state.finish(AgentExecutionStatus.STOPPED,
                        "Time budget of " + maxDuration.toSeconds() + "s exhausted.");
            }

            PlannerDecision decision;
            try {
                decision = planner.decide(question, availableTools, state);
            } catch (RuntimeException exception) {
                log.warn("Planner failed at step {}: {}", stepNumber, SafeDiagnostic.message(exception, 500));
                return state.finish(AgentExecutionStatus.FAILED,
                        "Planner error: " + safeMessage(exception));
            }

            if (decision == null || decision.isFinish()) {
                String reason = decision == null ? "planner returned no decision" : decision.finishReason();
                log.info("Planner finished at step {}: {}", stepNumber, reason);
                return state.finish(AgentExecutionStatus.COMPLETED, reason);
            }

            ToolSelectionPlan plan = decision.plan();
            String callKey = ToolCallKey.of(plan);
            if (!executedCalls.add(callKey)) {
                log.info("Planner repeated an identical call at step {} (tool={}); stopping", stepNumber, plan.toolName());
                return state.finish(AgentExecutionStatus.STOPPED,
                        "Stopped: the planner repeated an identical call to " + plan.toolName() + ".");
            }

            // Do not log tool arguments: they can contain user data or credentials.
            log.info(
                    "Tool selected: step={}, serverId={}, tool={}, argumentKeys={}, reason={}",
                    stepNumber, plan.serverId(), plan.toolName(), plan.arguments().keySet(), plan.reason());

            AgentExecutionStep step = toolExecutor.execute(stepNumber, plan);

            log.info(
                    "Tool execution result: step={}, tool={}, status={}, durationMs={}",
                    stepNumber, plan.toolName(), step.status(), step.durationMs());

            state = state.addStep(step);
            notify(progress, step);

            if (step.status() == AgentStepStatus.FAILED) {
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    return state.finish(AgentExecutionStatus.FAILED,
                            "Tool " + plan.toolName() + " failed"
                                    + (step.errorMessage() == null ? "" : ": " + step.errorMessage()));
                }
            } else {
                consecutiveFailures = 0;
            }
        }

        return state.finish(AgentExecutionStatus.STOPPED,
                "Step budget of " + maxSteps + " tool calls exhausted.");
    }

    private static void notify(AgentExecutionListener listener, AgentExecutionStep step) {
        try {
            listener.onStep(step);
        } catch (RuntimeException exception) {
            log.warn("Execution listener failed for step {}: {}", step.stepNumber(), SafeDiagnostic.message(exception, 500));
        }
    }

    private static String safeMessage(RuntimeException exception) {
        return SafeDiagnostic.message(exception, 500);
    }
}
