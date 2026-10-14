package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSearchQueryPolicyTest {
    private final McpToolCandidate search = new McpToolCandidate(UUID.randomUUID(), "search_messages",
            "Search messages", "Search Slack messages with keyword terms", Map.of("type", "object", "properties", Map.of(
            "natural_language_query", Map.of("type", "string"),
            "keywords", Map.of("type", "array", "items", Map.of("type", "string")))), true, "Slack", "slack");

    @Test
    void removesPlannerAddedExactPhraseQuotesWhenUserDidNotQuoteConcept() {
        Map<String, Object> result = SlackSearchQueryPolicy.normalize("Who messaged about eating pizza on Slack?", search,
                Map.of("natural_language_query", "Who mentioned \"eating pizza\"?", "keywords", List.of("\"eating pizza\"")));

        assertThat(result).containsEntry("natural_language_query", "Who mentioned eating pizza?")
                .containsEntry("keywords", List.of("eating pizza"));
    }

    @Test
    void preservesExplicitQuotedPhraseExactly() {
        Map<String, Object> result = SlackSearchQueryPolicy.normalize("Who said \"eating pizza\" in Slack?", search,
                Map.of("natural_language_query", "\"eating pizza\"", "keywords", List.of("\"eating pizza\"")));

        assertThat(result).containsEntry("natural_language_query", "\"eating pizza\"")
                .containsEntry("keywords", List.of("\"eating pizza\""));
    }

    @Test
    void doesNotRewriteOtherProvidersOrNonSearchSlackTools() {
        McpToolCandidate grafana = new McpToolCandidate(UUID.randomUUID(), "search_messages", "Search messages",
                "Search logs", search.inputSchema(), true, "Grafana", "grafana");

        assertThat(SlackSearchQueryPolicy.normalize("search Slack", grafana,
                Map.of("query", "\"eating pizza\""))).containsEntry("query", "\"eating pizza\"");
    }
}
