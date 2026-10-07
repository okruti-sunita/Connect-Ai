package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateGroundedAnswerGeneratorTest {

    @Test
    void producesDeterministicAnswerFromEvidence() {
        UUID serverId = UUID.randomUUID();
        ToolSelectionPlan plan = new ToolSelectionPlan(
                serverId,
                "search_jira",
                Map.of("query", "PAY-123"),
                "ticket");

        AgentExecutionState state = AgentExecutionState.initial("Why did it fail?")
                .addStep(new AgentExecutionStep(
                        1,
                        plan,
                        new ToolExecutionResult(
                                "search_jira",
                                false,
                                List.of(new ToolExecutionContent("text", "Ticket PAY-123 is blocked.")),
                                null),
                        AgentStepStatus.SUCCESS))
                .withStatus(AgentExecutionStatus.COMPLETED);

        GroundedAnswer answer = new TemplateGroundedAnswerGenerator(
                new GroundedAnswerContextBuilder()).generate(state);

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.answer()).contains("[E1]");
        assertThat(answer.answer()).contains("Ticket PAY-123 is blocked.");
        assertThat(answer.evidence()).hasSize(1);
    }

    @Test
    void clearlyReportsInsufficientEvidence() {
        AgentExecutionState state = AgentExecutionState.initial("Why did it fail?")
                .withStatus(AgentExecutionStatus.FAILED);

        GroundedAnswer answer = new TemplateGroundedAnswerGenerator(
                new GroundedAnswerContextBuilder()).generate(state);

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.answer())
                .contains("could not establish a reliable answer");
    }
}
