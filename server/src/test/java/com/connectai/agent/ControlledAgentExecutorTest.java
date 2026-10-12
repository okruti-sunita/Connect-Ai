package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ControlledAgentExecutorTest {

    private static final UUID SERVER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<McpToolCandidate> TOOLS = List.of(
            new McpToolCandidate(SERVER, "get_me", "", "Get the authenticated user", Map.of()),
            new McpToolCandidate(SERVER, "search", "", "Search", Map.of()));

    /** Planner that replays scripted decisions; a RuntimeException entry is thrown. */
    private static DynamicToolPlanner scripted(Object... decisions) {
        Deque<Object> queue = new ArrayDeque<>(List.of(decisions));
        return new DynamicToolPlanner() {
            @Override
            public java.util.Optional<ToolSelectionPlan> plan(String q, List<McpToolCandidate> t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PlannerDecision decide(String q, List<McpToolCandidate> t, AgentExecutionState s) {
                Object next = queue.isEmpty() ? PlannerDecision.finish("script exhausted") : queue.poll();
                if (next instanceof RuntimeException e) {
                    throw e;
                }
                return (PlannerDecision) next;
            }
        };
    }

    private static PlannerDecision run(String tool, Map<String, Object> args) {
        return PlannerDecision.run(new ToolSelectionPlan(SERVER, tool, args, "because"));
    }

    private static ToolExecutionPort port(boolean... errors) {
        Deque<Boolean> queue = new ArrayDeque<>();
        for (boolean e : errors) queue.add(e);
        return request -> {
            boolean error = !queue.isEmpty() && queue.poll();
            return new ToolExecutionResult(request.toolName(), error,
                    List.of(new ToolExecutionContent("text", error ? "boom" : "{\"login\":\"octo\"}")), null);
        };
    }

    private static ControlledAgentExecutor executor(DynamicToolPlanner planner, ToolExecutionPort port, int maxSteps) {
        return new ControlledAgentExecutor(planner, new PlannedToolExecutor(port), maxSteps, Duration.ofMinutes(1), 2);
    }

    @Test
    void noToolsFailsWithActionableReasonAndNeverCallsThePlanner() {
        AgentExecutionState state = executor(scripted(new IllegalStateException("must not be called")), port(), 3)
                .execute("q", List.of());

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.stopReason()).contains("No connected MCP tools");
    }

    @Test
    void finishAfterEvidenceCompletes() {
        List<AgentExecutionStep> seen = new ArrayList<>();
        AgentExecutionState state = executor(scripted(run("get_me", Map.of()), PlannerDecision.finish("enough")), port(), 5)
                .execute("q", TOOLS, seen::add);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(state.stopReason()).isEqualTo("enough");
        assertThat(state.steps()).hasSize(1);
        assertThat(seen).hasSize(1).first().extracting(AgentExecutionStep::status).isEqualTo(AgentStepStatus.SUCCESS);
    }

    @Test
    void plannerErrorFailsButKeepsEarlierSteps() {
        AgentExecutionState state = executor(scripted(run("get_me", Map.of()),
                new IllegalStateException("model unavailable")), port(), 5).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.stopReason()).contains("Planner error").contains("model unavailable");
        assertThat(state.steps()).hasSize(1);
    }

    @Test
    void identicalRepeatedCallStopsWithoutExecutingTwice() {
        AgentExecutionState state = executor(scripted(run("search", Map.of("q", "a")), run("search", Map.of("q", "a"))),
                port(), 5).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.STOPPED);
        assertThat(state.stopReason()).contains("repeated an identical call");
        assertThat(state.steps()).hasSize(1);
    }

    @Test
    void sameToolWithDifferentArgumentsIsAllowed() {
        AgentExecutionState state = executor(scripted(run("search", Map.of("q", "a")), run("search", Map.of("q", "b")),
                PlannerDecision.finish("done")), port(), 5).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(state.steps()).hasSize(2);
    }

    @Test
    void recoversFromOneFailedToolCall() {
        AgentExecutionState state = executor(scripted(run("search", Map.of()), run("get_me", Map.of()),
                PlannerDecision.finish("done")), port(true, false), 5).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(state.steps()).extracting(AgentExecutionStep::status)
                .containsExactly(AgentStepStatus.FAILED, AgentStepStatus.SUCCESS);
        assertThat(state.steps().get(0).errorMessage()).contains("Tool reported an error: boom");
    }

    @Test
    void consecutiveFailuresFailTheRun() {
        AgentExecutionState state = executor(scripted(run("search", Map.of()), run("get_me", Map.of())),
                port(true, true), 5).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.stopReason()).contains("get_me failed");
    }

    @Test
    void transportExceptionBecomesAFailedStepWithItsMessage() {
        ToolExecutionPort broken = request -> {
            throw new IllegalStateException("409 CONFLICT \"MCP server 'GitHub' is not connected\"");
        };
        AgentExecutionState state = executor(scripted(run("get_me", Map.of()), PlannerDecision.finish("x")), broken, 5)
                .execute("q", TOOLS);

        assertThat(state.steps().get(0).status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(state.steps().get(0).errorMessage()).contains("is not connected");
    }

    @Test
    void stepBudgetStops() {
        AgentExecutionState state = executor(scripted(run("search", Map.of("q", 1)), run("search", Map.of("q", 2))),
                port(), 2).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.STOPPED);
        assertThat(state.stopReason()).contains("Step budget of 2");
    }

    @Test
    void timeBudgetStops() {
        ControlledAgentExecutor executor = new ControlledAgentExecutor(
                scripted(run("search", Map.of("q", 1)), run("search", Map.of("q", 2))),
                new PlannedToolExecutor(request -> {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ToolExecutionResult(request.toolName(), false, List.of(), null);
                }),
                5, Duration.ofMillis(10), 2);

        AgentExecutionState state = executor.execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.STOPPED);
        assertThat(state.stopReason()).contains("Time budget");
    }
}
