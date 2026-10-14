package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaAwareSearchCapabilityTest {

    private static McpToolCandidate messageSearch() {
        return new McpToolCandidate(UUID.randomUUID(), "search_messages", "Search messages",
                "Search messages in this provider", Map.of("type", "object", "properties", Map.of(
                        "keywords", Map.of("type", "array", "items", Map.of("type", "string")),
                        "filters", Map.of("type", "string"),
                        "natural_language_query", Map.of("type", "string"))),
                true, "Slack - Test", "slack");
    }

    @Test
    void explicitProviderSearchCanCoverAnUnlistedSubjectWithoutInventingSupport() {
        McpToolCandidate search = messageSearch();
        List<String> questions = List.of(
                "Search Slack for recent messages mentioning deployment",
                "Search Slack for messages mentioning Connect AI",
                "Find the decisions, action items, and follow-ups in Slack");

        for (String question : questions) {
            ProviderConstraint.Scope scope = ProviderConstraint.apply(question, List.of(search));
            ToolShortlister.TopicCoverage coverage =
                    ToolShortlister.topicCoverage(question, scope.tools(), scope.constrained());
            assertThat(scope.constrained()).as(question).isTrue();
            assertThat(coverage.covering()).as(question).contains(search);
        }
    }

    @Test
    void searchCapabilityDoesNotBecomeAnUnscopedMatchForUnrelatedRequests() {
        McpToolCandidate search = messageSearch();

        ToolShortlister.TopicCoverage coverage = ToolShortlister.topicCoverage(
                "What is the latest deployment status?", List.of(search));

        assertThat(coverage.noToolCoversTheTopic()).isTrue();
        assertThat(coverage.covering()).isEmpty();
    }

    @Test
    void grafanaLogLabelsDoNotMatchProjectIssueLabels() {
        McpToolCandidate githubLabels = new McpToolCandidate(UUID.randomUUID(), "list_issue_labels", "List labels",
                "List repository issue labels", Map.of("type", "object"), true, "GitHub", "github");
        McpToolCandidate grafanaLabels = new McpToolCandidate(UUID.randomUUID(), "get_label_values", "Get label values",
                "Get label values for Grafana log data", Map.of("type", "object"), true, "Grafana", "grafana");

        ToolShortlister.TopicCoverage coverage = ToolShortlister.topicCoverage(
                "What log data do we have and what services do the labels show?", List.of(githubLabels, grafanaLabels));

        assertThat(coverage.covering()).contains(grafanaLabels).doesNotContain(githubLabels);
    }
}
