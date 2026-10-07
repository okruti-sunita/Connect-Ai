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
}
