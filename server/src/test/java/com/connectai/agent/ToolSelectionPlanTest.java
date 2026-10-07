package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSelectionPlanTest {

    @Test
    void convertsToApplicationExecutionRequest() {
        UUID serverId = UUID.randomUUID();

        ToolSelectionPlan plan = new ToolSelectionPlan(
                serverId,
                "search_jira",
                Map.of("query", "PAY-123"),
                "The ticket contains the relevant incident context.");

        ToolExecutionRequest request = plan.toExecutionRequest();

        assertThat(request.serverId()).isEqualTo(serverId);
        assertThat(request.toolName()).isEqualTo("search_jira");
        assertThat(request.arguments()).containsEntry("query", "PAY-123");
    }

    @Test
    void copiesArgumentsSoCallerMutationDoesNotChangePlan() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", "PAY-123");

        ToolSelectionPlan plan = new ToolSelectionPlan(
                UUID.randomUUID(),
                "search_jira",
                arguments,
                "Find the ticket.");

        arguments.put("query", "MALICIOUS-CHANGE");

        assertThat(plan.arguments()).containsEntry("query", "PAY-123");
    }

    @Test
    void rejectsBlankToolName() {
        assertThatThrownBy(() ->
                new ToolSelectionPlan(
                        UUID.randomUUID(),
                        " ",
                        Map.of(),
                        "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolName must not be blank");
    }
}
