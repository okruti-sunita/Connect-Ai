package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionStepTest {

    @Test
    void createsSuccessfulStep() {
        ToolSelectionPlan plan = plan("search_jira");

        ToolExecutionResult result = new ToolExecutionResult(
                "search_jira",
                false,
                List.of(),
                Map.of("issue", "PAY-123"));

        AgentExecutionStep step = new AgentExecutionStep(
                1,
                plan,
                result,
                AgentStepStatus.SUCCESS);

        assertThat(step.stepNumber()).isEqualTo(1);
        assertThat(step.plan()).isEqualTo(plan);
        assertThat(step.result()).isEqualTo(result);
        assertThat(step.status()).isEqualTo(AgentStepStatus.SUCCESS);
    }

    @Test
    void createsFailedStep() {
        ToolSelectionPlan plan = plan("search_jira");

        ToolExecutionResult result = new ToolExecutionResult(
                "search_jira",
                true,
                List.of(),
                null);

        AgentExecutionStep step = new AgentExecutionStep(
                2,
                plan,
                result,
                AgentStepStatus.FAILED);

        assertThat(step.status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(step.result().error()).isTrue();
    }

    @Test
    void zeroStepNumberIsRejected() {
        assertThatThrownBy(() -> new AgentExecutionStep(
                0,
                plan("search"),
                successfulResult("search"),
                AgentStepStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stepNumber must be greater than zero");
    }

    @Test
    void negativeStepNumberIsRejected() {
        assertThatThrownBy(() -> new AgentExecutionStep(
                -1,
                plan("search"),
                successfulResult("search"),
                AgentStepStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stepNumber must be greater than zero");
    }

    @Test
    void nullPlanIsRejected() {
        assertThatThrownBy(() -> new AgentExecutionStep(
                1,
                null,
                successfulResult("search"),
                AgentStepStatus.SUCCESS))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("plan");
    }

    @Test
    void nullResultIsRejected() {
        assertThatThrownBy(() -> new AgentExecutionStep(
                1,
                plan("search"),
                null,
                AgentStepStatus.SUCCESS))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("result");
    }

    @Test
    void nullStatusIsRejected() {
        assertThatThrownBy(() -> new AgentExecutionStep(
                1,
                plan("search"),
                successfulResult("search"),
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status");
    }

    private static ToolSelectionPlan plan(String toolName) {
        return new ToolSelectionPlan(
                UUID.randomUUID(),
                toolName,
                Map.of("query", "PAY-123"),
                "Relevant tool.");
    }

    private static ToolExecutionResult successfulResult(String toolName) {
        return new ToolExecutionResult(
                toolName,
                false,
                List.of(),
                Map.of("status", "ok"));
    }
}
