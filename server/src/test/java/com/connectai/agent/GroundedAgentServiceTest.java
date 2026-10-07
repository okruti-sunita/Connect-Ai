package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedAgentServiceTest {

    private static final UUID SERVER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void executesAgentThenGeneratesGroundedAnswerFromFinalState() {
        DynamicToolPlanner planner =
                (question, tools) -> Optional.of(new ToolSelectionPlan(
                        SERVER_ID,
                        "search_jira",
                        Map.of("query", "PAY-123"),
                        "ticket lookup"));

        ToolExecutionPort port = request -> new ToolExecutionResult(
                request.toolName(),
                false,
                List.of(new ToolExecutionContent("text", "PAY-123 is blocked.")),
                null);

        ControlledAgentExecutor executor = new ControlledAgentExecutor(
                planner,
                new PlannedToolExecutor(port),
                1);

        GroundedAnswerGenerator answerGenerator =
                new TemplateGroundedAnswerGenerator(
                        new GroundedAnswerContextBuilder());

        GroundedAgentResult result = new GroundedAgentService(
                executor,
                answerGenerator).execute(
                        "Why is payment blocked?",
                        List.of());

        assertThat(result.execution().status())
                .isEqualTo(AgentExecutionStatus.STOPPED);
        assertThat(result.execution().stepCount()).isEqualTo(1);
        assertThat(result.answer().evidence()).hasSize(1);
        assertThat(result.answer().answer()).contains("PAY-123 is blocked.");
    }
}
