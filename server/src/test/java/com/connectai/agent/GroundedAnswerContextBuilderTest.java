package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedAnswerContextBuilderTest {

    private static final UUID SERVER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void buildsEvidenceOnlyFromSuccessfulToolResults() {
        ToolSelectionPlan successfulPlan = new ToolSelectionPlan(
                SERVER_ID,
                "search_jira",
                Map.of("query", "PAY-123"),
                "ticket lookup");

        ToolSelectionPlan failedPlan = new ToolSelectionPlan(
                SERVER_ID,
                "search_logs",
                Map.of("query", "payment"),
                "runtime lookup");

        AgentExecutionState state = AgentExecutionState.initial("Why did payment fail?")
                .addStep(new AgentExecutionStep(
                        1,
                        successfulPlan,
                        new ToolExecutionResult(
                                "search_jira",
                                false,
                                List.of(new ToolExecutionContent("text", "PAY-123 was reopened.")),
                                null),
                        AgentStepStatus.SUCCESS))
                .addStep(new AgentExecutionStep(
                        2,
                        failedPlan,
                        new ToolExecutionResult(
                                "search_logs",
                                true,
                                List.of(),
                                null),
                        AgentStepStatus.FAILED));

        GroundedAnswerContext context =
                new GroundedAnswerContextBuilder().build(state);

        assertThat(context.evidence()).hasSize(1);
        assertThat(context.evidence().get(0).citation()).isEqualTo("E1");
        assertThat(context.evidence().get(0).toolName()).isEqualTo("search_jira");
        assertThat(context.evidence().get(0).content())
                .contains("PAY-123 was reopened.");
        assertThat(context.failedTools()).containsExactly("search_logs");
    }

    @Test
    void excludesToolResultMarkedAsErrorEvenWhenStepStatusIsSuccess() {
        ToolSelectionPlan plan = new ToolSelectionPlan(
                SERVER_ID, "search_logs", Map.of(), "runtime lookup");
        AgentExecutionState state = AgentExecutionState.initial("Investigate")
                .addStep(new AgentExecutionStep(
                        1, plan,
                        new ToolExecutionResult(
                                "search_logs", true,
                                List.of(new ToolExecutionContent("text", "do not use this as evidence")), null),
                        AgentStepStatus.SUCCESS));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.evidence()).isEmpty();
        assertThat(context.failedTools()).containsExactly("search_logs");
    }

    @Test
    void includesStructuredContentAsEvidence() {
        ToolSelectionPlan plan = new ToolSelectionPlan(
                SERVER_ID,
                "get_repository",
                Map.of(),
                "repository details");

        AgentExecutionState state = AgentExecutionState.initial("Find repository details")
                .addStep(new AgentExecutionStep(
                        1,
                        plan,
                        new ToolExecutionResult(
                                "get_repository",
                                false,
                                List.of(),
                                Map.of("name", "connect-ai", "private", true)),
                        AgentStepStatus.SUCCESS));

        GroundedAnswerContext context =
                new GroundedAnswerContextBuilder().build(state);

        assertThat(context.evidence()).hasSize(1);
        assertThat(context.evidence().get(0).content())
                .contains("connect-ai")
                .contains("private");
    }

    @Test
    void skipsSuccessfulResultWithNoUsableContent() {
        ToolSelectionPlan plan = new ToolSelectionPlan(
                SERVER_ID,
                "empty_tool",
                Map.of(),
                "test");

        AgentExecutionState state = AgentExecutionState.initial("Investigate")
                .addStep(new AgentExecutionStep(
                        1,
                        plan,
                        new ToolExecutionResult(
                                "empty_tool",
                                false,
                                List.of(),
                                null),
                        AgentStepStatus.SUCCESS));

        GroundedAnswerContext context =
                new GroundedAnswerContextBuilder().build(state);

        assertThat(context.evidence()).isEmpty();
    }

    @Test
    void boundsOversizedEvidence() {
        String large = "x".repeat(10_000);
        ToolSelectionPlan plan = new ToolSelectionPlan(
                SERVER_ID, "search_logs", Map.of(), "runtime lookup");
        AgentExecutionState state = AgentExecutionState.initial("Investigate")
                .addStep(new AgentExecutionStep(
                        1, plan,
                        new ToolExecutionResult(
                                "search_logs", false,
                                List.of(new ToolExecutionContent("text", large)), null),
                        AgentStepStatus.SUCCESS));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.evidence()).hasSize(1);
        assertThat(context.evidence().get(0).content().length()).isEqualTo(4_001);
        assertThat(context.evidence().get(0).content()).endsWith("…");
    }

    @Test
    void unresolvedNextPageIsReportedAndDoesNotDiscardEvidence() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of commits?")
                .addStep(step(1, Map.of("page", 1), Map.of("items", List.of(Map.of("sha", "abc")), "has_more", true)));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.evidence()).hasSize(1);
        assertThat(context.qualityWarnings()).anyMatch(warning -> warning.contains("additional pages"));
    }

    @Test
    void retrievedFinalPageResolvesPaginationForTotalEvidence() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of commits?")
                .addStep(step(1, Map.of("repo", "demo", "page", 1),
                        Map.of("items", List.of(Map.of("sha", "abc")), "has_more", true)))
                .addStep(step(2, Map.of("repo", "demo", "page", 2),
                        Map.of("items", List.of(Map.of("sha", "def")), "has_more", false)));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.evidence()).hasSize(2);
        assertThat(context.qualityWarnings()).noneMatch(warning -> warning.contains("additional pages"))
                .noneMatch(warning -> warning.contains("do not establish a complete total"));
    }

    @Test
    void overlappingPagesArePreservedButFlaggedForDeduplication() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of issues?")
                .addStep(step(1, Map.of("page", 1), Map.of("items", List.of(Map.of("key", "PAY-123")), "has_more", true)))
                .addStep(step(2, Map.of("page", 2), Map.of("items", List.of(Map.of("key", "PAY-123")), "has_more", false)));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.evidence()).hasSize(2);
        assertThat(context.qualityWarnings()).anyMatch(warning -> warning.contains("Duplicate records"));
    }

    @Test
    void listWithoutTotalOrPaginationCompletenessIsNotConsideredACompleteCount() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of issues?")
                .addStep(step(1, Map.of(), Map.of("items", List.of(Map.of("key", "PAY-123")))));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.qualityWarnings()).anyMatch(warning -> warning.contains("do not establish a complete total"));
    }

    @Test
    void yearQuestionRequiresSuccessfulToolArgumentsToScopeTheRequestedYear() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of commits in 2026?")
                .addStep(step(1, Map.of("owner", "octo", "repo", "demo"),
                        Map.of("total_count", 3, "incomplete_results", false)));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.qualityWarnings()).anyMatch(warning -> warning.contains("scoped its results to the requested year 2026"));
    }

    @Test
    void fullYearDateBoundsAreRecognizedAsExplicitScope() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of commits in 2026?")
                .addStep(new AgentExecutionStep(1,
                        new ToolSelectionPlan(SERVER_ID, "list_commits",
                                Map.of("since", "2026-01-01", "until", "2027-01-01"), "full year"),
                        new ToolExecutionResult("list_commits", false, List.of(),
                                Map.of("total_count", 3, "incomplete_results", false)), AgentStepStatus.SUCCESS));
        GroundedAnswerContext context = new GroundedAnswerContextBuilder().build(state);
        assertThat(context.qualityWarnings()).noneMatch(warning -> warning.contains("requested year 2026"));
    }

    private static AgentExecutionStep step(int number, Map<String, Object> args, Map<String, Object> result) {
        ToolSelectionPlan plan = new ToolSelectionPlan(SERVER_ID, "list_issues", args, "page results");
        return new AgentExecutionStep(number, plan,
                new ToolExecutionResult("list_issues", false, List.of(), result), AgentStepStatus.SUCCESS);
    }
}
