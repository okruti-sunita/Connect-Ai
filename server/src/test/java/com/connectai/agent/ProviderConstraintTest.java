package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConstraintTest {
    @Test
    void explicitSlackQuestionExcludesLinearTools() {
        McpToolCandidate slack = new McpToolCandidate(UUID.randomUUID(), "search_messages", "",
                "Search messages", Map.of(), true, "Slack - Workspace", "slack");
        McpToolCandidate linear = new McpToolCandidate(UUID.randomUUID(), "search_issues", "",
                "Search issues", Map.of(), true, "Linear - Workspace", "linear");

        ProviderConstraint.Scope scope = ProviderConstraint.apply(
                "Find the DEMO message in Slack", List.of(linear, slack));

        assertTrue(scope.constrained());
        assertEquals(List.of(slack), scope.tools());
        assertEquals(java.util.Set.of("slack"), scope.providerKeys());
    }

    @Test
    void explicitCrossProviderQuestionKeepsBothNamedProviders() {
        McpToolCandidate slack = new McpToolCandidate(UUID.randomUUID(), "search_messages", "",
                "Search messages", Map.of(), true, "Slack - Workspace", "slack");
        McpToolCandidate linear = new McpToolCandidate(UUID.randomUUID(), "search_issues", "",
                "Search issues", Map.of(), true, "Linear - Workspace", "linear");

        ProviderConstraint.Scope scope = ProviderConstraint.apply(
                "Compare the Slack incident thread with related Linear issues", List.of(slack, linear));

        assertTrue(scope.constrained());
        assertEquals(List.of(slack, linear), scope.tools());
        assertEquals(java.util.Set.of("slack", "linear"), scope.providerKeys());
    }

    @Test
    void explicitGrafanaQuestionsDoNotRouteToOtherProviders() {
        McpToolCandidate grafana = new McpToolCandidate(UUID.randomUUID(), "query_prometheus", "",
                "Query Grafana metrics", Map.of(), true, "Grafana - Production", "grafana");
        McpToolCandidate github = new McpToolCandidate(UUID.randomUUID(), "search_github_issues", "",
                "Search issues", Map.of(), true, "GitHub - Main", "github");

        ProviderConstraint.Scope scope = ProviderConstraint.apply(
                "In Grafana, show errors in the last hour", List.of(grafana, github));

        assertTrue(scope.constrained());
        assertEquals(List.of(grafana), scope.tools());
        assertEquals(java.util.Set.of("grafana"), scope.providerKeys());
    }
}
