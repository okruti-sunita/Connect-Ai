package com.connectai.service;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.TemplateGroundedAnswerGenerator;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.domain.InvestigationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationFailureStatusTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final TemplateGroundedAnswerGenerator generator = new TemplateGroundedAnswerGenerator(new GroundedAnswerContextBuilder());

    private AgentExecutionState successful(String question, String response) {
        var plan = new ToolSelectionPlan(SERVER, "search_messages", Map.of(), "read-only search");
        return AgentExecutionState.initial(question).addStep(new com.connectai.agent.AgentExecutionStep(1, plan,
                new ToolExecutionResult("search_messages", false,
                        List.of(new ToolExecutionContent("text", response)), null), AgentStepStatus.SUCCESS));
    }

    @Test
    void successfulButIrrelevantEvidenceThenRateLimitIsPartial() {
        AgentExecutionState failed = successful("Find the release incident", "Lunch is at noon.")
                .finish(AgentExecutionStatus.FAILED, "LLM provider rate limit (HTTP 429).");
        GroundedAnswer answer = generator.generate(failed);

        assertThat(InvestigationStore.statusFor(failed, answer)).isEqualTo(InvestigationStatus.PARTIAL);
        assertThat(answer.executionStatus()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.NONE);
        assertThat(answer.evidence()).hasSize(1);
    }

    @Test
    void failedPlanningWithoutEvidenceIsFailedAndNotAnEmptyAnswer() {
        AgentExecutionState failed = AgentExecutionState.initial("Search incidents")
                .finish(AgentExecutionStatus.FAILED, "The investigation planner failed before completion.");
        GroundedAnswer answer = generator.generate(failed);

        assertThat(InvestigationStore.statusFor(failed, answer)).isEqualTo(InvestigationStatus.FAILED);
        assertThat(answer.evidence()).isEmpty();
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.NONE);
        assertThat(answer.answer()).doesNotContain("no matching records");
    }

    @Test
    void completedExplicitEmptySearchIsAnswered() {
        AgentExecutionState completed = successful("Search Slack messages", "No results found.")
                .finish(AgentExecutionStatus.COMPLETED, "complete");
        GroundedAnswer answer = generator.generate(completed);

        assertThat(InvestigationStore.statusFor(completed, answer)).isEqualTo(InvestigationStatus.ANSWERED);
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.FULL);
    }
}
