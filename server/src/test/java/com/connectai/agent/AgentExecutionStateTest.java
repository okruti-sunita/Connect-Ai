package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionStateTest {

    @Test
    void initialStateStartsRunningWithNoSteps() {
        AgentExecutionState state =
                AgentExecutionState.initial("Find what happened to PAY-123");

        assertThat(state.question())
                .isEqualTo("Find what happened to PAY-123");
        assertThat(state.steps()).isEmpty();
        assertThat(state.status())
                .isEqualTo(AgentExecutionStatus.RUNNING);
        assertThat(state.stepCount()).isZero();
    }

    @Test
    void addStepReturnsNewStateWithoutMutatingOriginal() {
        AgentExecutionState original =
                AgentExecutionState.initial("Find PAY-123");

        AgentExecutionStep step =
                successfulStep(1, "search_jira");

        AgentExecutionState updated = original.addStep(step);

        assertThat(original.steps()).isEmpty();
        assertThat(updated.steps()).containsExactly(step);
        assertThat(updated.stepCount()).isEqualTo(1);
        assertThat(updated.status())
                .isEqualTo(AgentExecutionStatus.RUNNING);
    }

    @Test
    void multipleStepsPreserveExecutionOrder() {
        AgentExecutionState state =
                AgentExecutionState.initial("Investigate payment failure");

        AgentExecutionStep first = successfulStep(1, "search_jira");
        AgentExecutionStep second = successfulStep(2, "search_logs");

        state = state.addStep(first);
        state = state.addStep(second);

        assertThat(state.steps()).containsExactly(first, second);
    }

    @Test
    void statusCanBeChangedWithoutMutatingPreviousState() {
        AgentExecutionState running =
                AgentExecutionState.initial("Investigate incident");

        AgentExecutionState completed = running.withStatus(
                AgentExecutionStatus.COMPLETED);

        assertThat(running.status())
                .isEqualTo(AgentExecutionStatus.RUNNING);
        assertThat(completed.status())
                .isEqualTo(AgentExecutionStatus.COMPLETED);
    }

    @Test
    void blankQuestionIsRejected() {
        assertThatThrownBy(() ->
                AgentExecutionState.initial(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("question must not be blank");
    }

    @Test
    void nullQuestionIsRejected() {
        assertThatThrownBy(() ->
                AgentExecutionState.initial(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("question");
    }

    @Test
    void nullStepIsRejected() {
        AgentExecutionState state =
                AgentExecutionState.initial("Investigate incident");

        assertThatThrownBy(() -> state.addStep(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("step");
    }

    @Test
    void stepsAreImmutable() {
        AgentExecutionState state =
                AgentExecutionState.initial("Investigate incident")
                        .addStep(successfulStep(1, "search_jira"));

        assertThatThrownBy(() ->
                state.steps().add(successfulStep(2, "search_logs")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AgentExecutionStep successfulStep(
            int stepNumber,
            String toolName) {

        ToolSelectionPlan plan = new ToolSelectionPlan(
                UUID.randomUUID(),
                toolName,
                Map.of("query", "PAY-123"),
                "The tool can provide relevant information.");

        ToolExecutionResult result = new ToolExecutionResult(
                toolName,
                false,
                List.of(),
                Map.of("status", "ok"));

        return new AgentExecutionStep(
                stepNumber,
                plan,
                result,
                AgentStepStatus.SUCCESS);
    }
}
