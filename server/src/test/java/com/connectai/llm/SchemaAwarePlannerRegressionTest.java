package com.connectai.llm;

import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.GroundedAnswerContext;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSelectionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaAwarePlannerRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void plannerReceivesTheFullOperationGuidanceAndSearchSchemaAndProducesValidArguments() {
        UUID connection = UUID.randomUUID();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : List.of("limit", "sort", "cursor", "channel_types", "content_types",
                "context_channel_id", "keywords", "filters", "natural_language_query")) {
            properties.put(name, switch (name) {
                case "limit" -> Map.of("type", "integer");
                case "keywords" -> Map.of("type", "array", "items", Map.of("type", "string"));
                default -> Map.of("type", "string");
            });
        }
        String description = "Search messages in the selected provider. "
                + "Use keywords for lexical terms and natural_language_query for the original request. "
                + "The query must include keywords or filters; a limit alone is not a search.";
        McpToolCandidate search = new McpToolCandidate(connection, "search_messages", "Search messages",
                description, Map.of("type", "object", "properties", properties), true,
                "Slack - Test", "slack");
        RecordingClient client = new RecordingClient("""
                {"action":"T1","arguments":{"keywords":["deployment"],
                 "natural_language_query":"Search Slack messages mentioning deployment",
                 "limit":5},"reason":"Search the requested provider messages."}
                """);

        ToolSelectionPlan plan = new LlmMcpToolPlanner(client, 6, 2)
                .plan("Search Slack for the 5 most recent messages that mention deployment",
                        List.of(search)).orElseThrow();

        assertThat(plan.serverId()).isEqualTo(connection);
        assertThat(plan.toolName()).isEqualTo("search_messages");
        assertThat(plan.arguments()).containsEntry("keywords", List.of("deployment"))
                .containsEntry("limit", 5L)
                .containsKey("natural_language_query");
        assertThat(client.userPrompt).contains("keywords", "natural_language_query", "limit")
                .contains("a limit alone is not a search");
    }

    @Test
    void explicitSlackQuestionCannotSelectAnotherProvidersSearchTool() {
        UUID slackId = UUID.randomUUID();
        UUID githubId = UUID.randomUUID();
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
        McpToolCandidate slack = new McpToolCandidate(slackId, "search_messages", "Search messages",
                "Search messages using free-text keywords", schema, true, "Slack - Work", "slack");
        McpToolCandidate github = new McpToolCandidate(githubId, "search_code", "Search code",
                "Search code using free-text query", schema, true, "GitHub - Personal", "github");
        RecordingClient client = new RecordingClient("{\"action\":\"T1\",\"arguments\":{\"query\":\"deployment\"},\"reason\":\"Slack search\"}");

        ToolSelectionPlan plan = new LlmMcpToolPlanner(client, 4, 1)
                .plan("Search Slack for deployment", List.of(github, slack)).orElseThrow();

        assertThat(plan.serverId()).isEqualTo(slackId);
        assertThat(client.userPrompt).contains("search_messages").doesNotContain("search_code");
    }

    @Test
    void providerArgumentFailureIsRetriedWithCorrectedArgumentsAndSuccessfulEvidenceSurvives() {
        UUID connection = UUID.randomUUID();
        McpToolCandidate search = new McpToolCandidate(connection, "search_messages", "Search messages",
                "Search messages in the explicitly selected provider", Map.of("type", "object", "properties", Map.of(
                        "keywords", Map.of("type", "array", "items", Map.of("type", "string")),
                        "filters", Map.of("type", "string"),
                        "natural_language_query", Map.of("type", "string"),
                        "limit", Map.of("type", "integer"))), true, "Slack - Test", "slack");
        SequenceClient client = new SequenceClient(List.of(
                "{\"action\":\"T1\",\"arguments\":{\"query\":\"deployment\",\"limit\":5},\"reason\":\"Wrong field name.\"}",
                "{\"action\":\"T1\",\"arguments\":{\"limit\":5},\"reason\":\"Retry without the query fields.\"}",
                "{\"action\":\"T1\",\"arguments\":{\"keywords\":[\"deployment\"],\"natural_language_query\":\"Search messages mentioning deployment\",\"limit\":5},\"reason\":\"Repair with the schema fields.\"}",
                "{\"action\":\"FINISH\",\"arguments\":{},\"reason\":\"Evidence is available.\"}"));
        java.util.ArrayList<Map<String, Object>> invokedArguments = new java.util.ArrayList<>();
        ToolExecutionPort providerFixture = request -> {
            invokedArguments.add(request.arguments());
            if (!request.arguments().containsKey("keywords") || !request.arguments().containsKey("natural_language_query")) {
                return new ToolExecutionResult(request.toolName(), true,
                        List.of(new ToolExecutionContent("text", "parameter_validation_failed: keywords or filters and a natural language query are required")), null);
            }
            return new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "{\"fixture\":\"fabricated\",\"records\":[]}")), null);
        };

        var state = new ControlledAgentExecutor(new LlmMcpToolPlanner(client, 6, 2),
                new PlannedToolExecutor(providerFixture), 4, Duration.ofSeconds(10), 3)
                .execute("Search Slack for messages mentioning deployment", List.of(search));
        GroundedAnswerContext evidence = new GroundedAnswerContextBuilder().build(state);

        assertThat(state.steps()).hasSize(2);
        assertThat(state.steps().get(0).status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(state.steps().get(1).status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(client.prompts.get(1)).contains("arguments.query is not a parameter");
        assertThat(client.prompts.get(2)).contains("parameter_validation_failed");
        assertThat(invokedArguments).hasSize(2);
        assertThat(invokedArguments.getFirst()).containsOnlyKeys("limit");
        assertThat(evidence.evidence()).hasSize(1);
        assertThat(evidence.evidence().getFirst().serverId()).isEqualTo(connection);
        assertThat(evidence.evidence().getFirst().toolName()).isEqualTo("search_messages");
        assertThat(evidence.evidence().getFirst().content()).contains("fabricated");
    }

    @Test
    void plannerRemovesModelAddedSlackExactPhraseQuotesButPreservesUserExactPhrase() {
        UUID connection = UUID.randomUUID();
        McpToolCandidate search = new McpToolCandidate(connection, "search_messages", "Search messages",
                "Search Slack messages", Map.of("type", "object", "properties", Map.of(
                        "natural_language_query", Map.of("type", "string"),
                        "keywords", Map.of("type", "array", "items", Map.of("type", "string")))),
                true, "Slack", "slack");

        RecordingClient ordinary = new RecordingClient("{\"action\":\"T1\",\"arguments\":{\"natural_language_query\":\"\\\"eating pizza\\\"\",\"keywords\":[\"\\\"eating pizza\\\"\"]},\"reason\":\"search\"}");
        ToolSelectionPlan ordinaryPlan = new LlmMcpToolPlanner(ordinary, 3, 1)
                .plan("Who messaged about eating pizza on Slack?", List.of(search)).orElseThrow();
        assertThat(ordinaryPlan.arguments()).containsEntry("natural_language_query", "eating pizza")
                .containsEntry("keywords", List.of("eating pizza"));

        RecordingClient exact = new RecordingClient("{\"action\":\"T1\",\"arguments\":{\"natural_language_query\":\"\\\"eating pizza\\\"\",\"keywords\":[\"\\\"eating pizza\\\"\"]},\"reason\":\"exact\"}");
        ToolSelectionPlan exactPlan = new LlmMcpToolPlanner(exact, 3, 1)
                .plan("Who messaged \"eating pizza\" on Slack?", List.of(search)).orElseThrow();
        assertThat(exactPlan.arguments()).containsEntry("natural_language_query", "\"eating pizza\"");
    }

    @Test
    void grafanaPromptRequiresDatasourceDiscoveryBeforeAnInventedUid() {
        UUID connection = UUID.randomUUID();
        McpToolCandidate listing = new McpToolCandidate(connection, "list_data_sources", "List datasources",
                "List available Grafana datasources", Map.of("type", "object"), true, "Grafana", "grafana");
        McpToolCandidate query = new McpToolCandidate(connection, "query_logs", "Query logs",
                "Query Loki logs", Map.of("type", "object", "properties", Map.of(
                "datasourceUid", Map.of("type", "string"), "query", Map.of("type", "string"))),
                true, "Grafana", "grafana");

        String prompt = LlmMcpToolPlanner.buildPrompt("What log labels are available?",
                Map.of("T1", listing, "T2", query), AgentExecutionState.initial("What log labels are available?"));
        assertThat(prompt).contains("choose the discovered datasource listing tool first")
                .contains("never invent a datasource identifier");
    }

    @Test
    void slackRefinementInstructionIsBoundedAndPaginationDoesNotConsumeQueryVariantBudget() {
        UUID connection = UUID.randomUUID();
        McpToolCandidate search = new McpToolCandidate(connection, "search_messages", "Search messages",
                "Search Slack messages", Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string"), "cursor", Map.of("type", "string"))), true, "Slack", "slack");
        var pageOne = step(1, connection, Map.of("query", "pizza"), "{\"messages\":[{\"id\":\"a\"}],\"next_cursor\":\"p2\"}");
        var pageTwo = step(2, connection, Map.of("query", "pizza", "cursor", "p2"), "{\"messages\":[{\"id\":\"b\"}]}");
        AgentExecutionState paged = AgentExecutionState.initial("Find pizza messages").addStep(pageOne).addStep(pageTwo);
        String pagePrompt = LlmMcpToolPlanner.buildPrompt("Find pizza messages", Map.of("T1", search), paged);
        assertThat(pagePrompt).doesNotContain("Slack query refinement limit reached");

        var variant = step(3, connection, Map.of("query", "piz*"), "{\"messages\":[{\"id\":\"c\"}]}");
        AgentExecutionState refined = paged.addStep(variant);
        String refinedPrompt = LlmMcpToolPlanner.buildPrompt("Find pizza messages", Map.of("T1", search), refined);
        assertThat(refinedPrompt).contains("Slack query refinement limit reached");
    }

    private static com.connectai.agent.AgentExecutionStep step(int number, UUID serverId,
            Map<String, Object> arguments, String result) {
        return new com.connectai.agent.AgentExecutionStep(number,
                new ToolSelectionPlan(serverId, "search_messages", arguments, "fixture"),
                new ToolExecutionResult("search_messages", false,
                        List.of(new ToolExecutionContent("text", result)), null),
                AgentStepStatus.SUCCESS);
    }

    private final class RecordingClient implements LlmClient {
        private final String response;
        private String userPrompt;

        private RecordingClient(String response) { this.response = response; }

        @Override
        public JsonNode callTool(String system, String user, ToolSpec tool) {
            userPrompt = user;
            try { return mapper.readTree(response); }
            catch (Exception exception) { throw new AssertionError(exception); }
        }

        @Override
        public String complete(String system, String user) { return ""; }
    }

    private final class SequenceClient implements LlmClient {
        private final List<String> responses;
        private final java.util.ArrayList<String> prompts = new java.util.ArrayList<>();
        private int index;

        private SequenceClient(List<String> responses) { this.responses = responses; }

        @Override
        public JsonNode callTool(String system, String user, ToolSpec tool) {
            prompts.add(user);
            try { return mapper.readTree(responses.get(index++)); }
            catch (Exception exception) { throw new AssertionError(exception); }
        }

        @Override
        public String complete(String system, String user) { return ""; }
    }
}
