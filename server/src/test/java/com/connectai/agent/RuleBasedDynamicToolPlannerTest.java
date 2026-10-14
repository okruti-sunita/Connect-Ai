package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedDynamicToolPlannerTest {

    @Test
    void semanticFallbackCanSelectOnlyAnArgumentFreeLiveTool() {
        McpToolCandidate live = new McpToolCandidate(UUID.randomUUID(), "query_feedback_rollup", "Feedback rollup",
                "Aggregate customer satisfaction signals", Map.of("type", "object"));
        ToolSemanticRetriever semantic = (question, tools, limit) -> List.of(live);

        var plan = new RuleBasedDynamicToolPlanner(semantic)
                .plan("What caused production incidents?", List.of(live)).orElseThrow();

        assertThat(plan.serverId()).isEqualTo(live.serverId());
        assertThat(plan.toolName()).isEqualTo(live.toolName());
        assertThat(plan.arguments()).isEmpty();
    }

    @Test
    void semanticFallbackCannotSelectStaleOrRequiredArgumentTools() {
        McpToolCandidate live = new McpToolCandidate(UUID.randomUUID(), "query_feedback_rollup", "Feedback rollup",
                "Aggregate customer satisfaction signals", Map.of("type", "object"));
        McpToolCandidate stale = new McpToolCandidate(UUID.randomUUID(), "search_incidents", "Incidents",
                "Search incident data", Map.of("type", "object"));
        ToolSemanticRetriever staleRetriever = (question, tools, limit) -> List.of(stale);

        assertThat(new RuleBasedDynamicToolPlanner(staleRetriever)
                .plan("What caused production incidents?", List.of(live))).isEmpty();

        McpToolCandidate needsArguments = new McpToolCandidate(UUID.randomUUID(), "query_incidents", "Incidents",
                "Search incident data", Map.of("type", "object", "required", List.of("query")));
        ToolSemanticRetriever requiredRetriever = (question, tools, limit) -> List.of(needsArguments);
        assertThat(new RuleBasedDynamicToolPlanner(requiredRetriever)
                .plan("What caused production incidents?", List.of(needsArguments))).isEmpty();
    }

    @Test
    void noAcceptedSemanticMatchDoesNotChooseAnUnrelatedLiveTool() {
        McpToolCandidate unrelated = new McpToolCandidate(UUID.randomUUID(), "query_feedback_rollup", "Feedback rollup",
                "Aggregate customer satisfaction signals", Map.of("type", "object"));
        ToolSemanticRetriever rejectedWeakMatch = (question, tools, limit) -> List.of();

        assertThat(new RuleBasedDynamicToolPlanner(rejectedWeakMatch)
                .plan("What caused production incidents?", List.of(unrelated))).isEmpty();
    }
}
