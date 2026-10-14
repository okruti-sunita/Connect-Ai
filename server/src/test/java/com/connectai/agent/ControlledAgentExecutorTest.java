package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    void rejectsAPlannerSelectionOutsideTheLiveServerAndToolCatalog() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionPort port = request -> {
            calls.incrementAndGet();
            return new ToolExecutionResult(request.toolName(), false, List.of(), null);
        };
        ToolSelectionPlan wrongServer = new ToolSelectionPlan(UUID.randomUUID(), "get_me", Map.of(), "stale");

        AgentExecutionState state = executor(scripted(PlannerDecision.run(wrongServer)), port, 3)
                .execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.stopReason()).contains("not available").contains("No tool was executed");
        assertThat(state.steps()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void explicitProviderIsAConstraintForEveryDynamicPlanner() {
        UUID github = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID linear = UUID.fromString("00000000-0000-0000-0000-000000000003");
        McpToolCandidate githubTool = new McpToolCandidate(github, "list_issues", "", "List issues", Map.of(),
                true, "GitHub", "github");
        McpToolCandidate linearTool = new McpToolCandidate(linear, "list_issues", "", "List issues", Map.of(),
                true, "Linear", "linear");
        List<List<McpToolCandidate>> plannerCatalogs = new ArrayList<>();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger executedServer = new AtomicInteger();
        DynamicToolPlanner planner = new DynamicToolPlanner() {
            @Override
            public java.util.Optional<ToolSelectionPlan> plan(String q, List<McpToolCandidate> t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PlannerDecision decide(String q, List<McpToolCandidate> t, AgentExecutionState s) {
                plannerCatalogs.add(List.copyOf(t));
                return plannerCalls.getAndIncrement() == 0
                        ? PlannerDecision.run(new ToolSelectionPlan(linear, "list_issues", Map.of(), "Linear requested"))
                        : PlannerDecision.finish("done");
            }
        };
        ToolExecutionPort executionPort = request -> {
            if (request.serverId().equals(linear)) executedServer.set(3);
            if (request.serverId().equals(github)) executedServer.set(2);
            return new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "[]")), null);
        };

        AgentExecutionState state = executor(planner, executionPort, 2)
                .execute("List my tasks on Linear", List.of(githubTool, linearTool));

        assertThat(plannerCatalogs).isNotEmpty().allSatisfy(catalog -> assertThat(catalog).containsExactly(linearTool));
        assertThat(executedServer).hasValue(3);
        assertThat(state.steps()).hasSize(1);
    }

    @Test
    void validatesArgumentsAgainstTheLiveSchemaBeforeExternalExecutionAndCanRecover() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionPort port = request -> {
            calls.incrementAndGet();
            return new ToolExecutionResult(request.toolName(), false, List.of(), null);
        };
        McpToolCandidate strict = new McpToolCandidate(SERVER, "search", "", "Search",
                Map.of("type", "object", "required", List.of("query"),
                        "properties", Map.of("query", Map.of("type", "string")),
                        "additionalProperties", false));
        List<McpToolCandidate> catalog = List.of(strict);

        AgentExecutionState state = executor(scripted(run("search", Map.of()), run("search", Map.of("query", "incident")),
                PlannerDecision.finish("done")), port, 4).execute("q", catalog);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(state.steps()).extracting(AgentExecutionStep::status)
                .containsExactly(AgentStepStatus.FAILED, AgentStepStatus.SUCCESS);
        assertThat(state.steps().get(0).errorMessage()).contains("rejected before external execution").contains("query is required");
        assertThat(calls).hasValue(1);
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
    void plannerErrorFailsSafelyButKeepsEarlierSteps() {
        AgentExecutionState state = executor(scripted(run("get_me", Map.of()),
                new IllegalStateException("model unavailable")), port(), 5).execute("q", TOOLS);

        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.stopReason()).contains("planner failed").doesNotContain("model unavailable");
        assertThat(state.failureCategory()).isEqualTo(AgentFailureCategory.PLANNER_FAILURE);
        assertThat(state.steps()).hasSize(1);
    }

    @Test
    void rateLimitAfterOneSuccessfulSlackSearchPreservesEvidenceAndRetryHint() {
        McpToolCandidate slackSearch = new McpToolCandidate(SERVER, "search_messages", "", "Search Slack messages",
                Map.of(), true, "Slack - Test", "slack");
        AgentExecutionState state = executor(scripted(run("search_messages", Map.of()),
                new PlannerFailureException(AgentFailureCategory.PROVIDER_RATE_LIMIT,
                        "LLM provider rate limit (HTTP 429). Retry after 17 seconds.", 17L)), port(), 5)
                .execute("Search Slack messages", List.of(slackSearch));

        assertThat(state.steps()).hasSize(1);
        assertThat(state.hasSuccessfulStep()).isTrue();
        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.failureCategory()).isEqualTo(AgentFailureCategory.PROVIDER_RATE_LIMIT);
        assertThat(state.stopReason()).contains("429", "17 seconds");
    }

    @Test
    void rateLimitAfterTwoReadOnlyStepsPreservesBothSuccessfulSteps() {
        AgentExecutionState state = executor(scripted(run("get_me", Map.of()), run("search", Map.of()),
                new PlannerFailureException(AgentFailureCategory.PROVIDER_RATE_LIMIT,
                        "LLM provider rate limit (HTTP 429).", null)), port(), 5)
                .execute("q", TOOLS);

        assertThat(state.steps()).hasSize(2).extracting(AgentExecutionStep::status)
                .containsExactly(AgentStepStatus.SUCCESS, AgentStepStatus.SUCCESS);
        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.failureCategory()).isEqualTo(AgentFailureCategory.PROVIDER_RATE_LIMIT);
    }

    @Test
    void plannerFailureBeforeExecutionHasNoEvidenceAndSafeFailureCategory() {
        AgentExecutionState state = executor(scripted(new PlannerFailureException(
                AgentFailureCategory.PLANNER_FAILURE, "The investigation planner failed before completion.", null)),
                port(), 5).execute("q", TOOLS);

        assertThat(state.steps()).isEmpty();
        assertThat(state.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(state.failureCategory()).isEqualTo(AgentFailureCategory.PLANNER_FAILURE);
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

    @Test
    void explicitNextCursorIsFetchedDeterministicallyBeforePlannerCanFinish() {
        McpToolCandidate search = new McpToolCandidate(SERVER, "search_messages", "Search messages",
                "Search messages", Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string"), "cursor", Map.of("type", "string"))),
                true, "Slack", "slack");
        List<Map<String, Object>> arguments = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        ToolExecutionPort provider = request -> {
            arguments.add(request.arguments());
            return invocation.getAndIncrement() == 0
                    ? new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "{\"next_cursor\":\"cursor-2\"}")), null)
                    : new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "{\"messages\":[{\"id\":\"m2\"}]}")), null);
        };

        AgentExecutionState state = executor(scripted(run("search_messages", Map.of("query", "pizza")),
                PlannerDecision.finish("enough evidence")), provider, 5)
                .execute("Search Slack for pizza", List.of(search));

        assertThat(state.steps()).hasSize(2);
        assertThat(arguments).containsExactly(Map.of("query", "pizza"), Map.of("query", "pizza", "cursor", "cursor-2"));
        assertThat(state.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
    }
}
