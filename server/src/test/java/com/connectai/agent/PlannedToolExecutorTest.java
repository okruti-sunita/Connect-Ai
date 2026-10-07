package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlannedToolExecutorTest {

    private static final UUID SERVER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void execute_delegatesPlanToExecutionPortAndReturnsSuccessfulStep() {
        AtomicReference<ToolExecutionRequest> captured =
                new AtomicReference<>();

        ToolExecutionPort port = request -> {
            captured.set(request);
            return new ToolExecutionResult(
                    request.toolName(),
                    false,
                    List.of(),
                    Map.of("count", 2));
        };

        ToolSelectionPlan plan = plan();
        PlannedToolExecutor executor = new PlannedToolExecutor(port);

        AgentExecutionStep step = executor.execute(1, plan);

        assertThat(captured.get().serverId()).isEqualTo(SERVER_ID);
        assertThat(captured.get().toolName()).isEqualTo("search_jira");
        assertThat(captured.get().arguments())
                .containsEntry("query", "PAY-123");

        assertThat(step.stepNumber()).isEqualTo(1);
        assertThat(step.plan()).isEqualTo(plan);
        assertThat(step.status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(step.result().error()).isFalse();
        assertThat(step.result().structuredContent())
                .isEqualTo(Map.of("count", 2));
    }

    @Test
    void execute_marksToolLevelErrorAsFailed() {
        ToolExecutionPort port = request ->
                new ToolExecutionResult(
                        request.toolName(),
                        true,
                        List.of(),
                        null);

        AgentExecutionStep step =
                new PlannedToolExecutor(port).execute(2, plan());

        assertThat(step.status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(step.result().error()).isTrue();
    }

    @Test
    void execute_convertsExecutionExceptionIntoFailedStep() {
        ToolExecutionPort port = request -> {
            throw new IllegalStateException("MCP unavailable");
        };

        AgentExecutionStep step =
                new PlannedToolExecutor(port).execute(3, plan());

        assertThat(step.status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(step.result().error()).isTrue();
        assertThat(step.result().toolName()).isEqualTo("search_jira");
        assertThat(step.result().content()).isEmpty();
    }

    @Test
    void execute_rejectsInvalidStepNumber() {
        ToolExecutionPort port = request ->
                new ToolExecutionResult(
                        request.toolName(),
                        false,
                        List.of(),
                        null);

        assertThatThrownBy(() ->
                new PlannedToolExecutor(port).execute(0, plan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stepNumber must be greater than zero");
    }

    @Test
    void execute_rejectsNullPlan() {
        ToolExecutionPort port = request ->
                new ToolExecutionResult(
                        request.toolName(),
                        false,
                        List.of(),
                        null);

        assertThatThrownBy(() ->
                new PlannedToolExecutor(port).execute(1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("plan");
    }

    @Test
    void execute_rejectsNullExecutionPort() {
        assertThatThrownBy(() ->
                new PlannedToolExecutor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("toolExecutionPort");
    }

    @Test
    void execute_treatsNullExecutionResultAsFailedStep() {
        ToolExecutionPort port = request -> null;

        AgentExecutionStep step =
                new PlannedToolExecutor(port).execute(4, plan());

        assertThat(step.status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(step.result().error()).isTrue();
        assertThat(step.result().toolName()).isEqualTo("search_jira");
    }

    private static ToolSelectionPlan plan() {
        return new ToolSelectionPlan(
                SERVER_ID,
                "search_jira",
                Map.of("query", "PAY-123"),
                "The question references the payment ticket.");
    }
}
