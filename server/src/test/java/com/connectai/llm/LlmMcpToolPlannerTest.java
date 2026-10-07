package com.connectai.llm;

import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ToolSelectionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmMcpToolPlannerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsAllowedToolAndReturnsArguments() {
        UUID serverId = UUID.randomUUID();

        McpToolCandidate jira = new McpToolCandidate(
                serverId,
                "search_jira",
                "Search Jira",
                "Search Jira issues",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string")),
                        "required", List.of("query")));

        LlmClient llm = new StubLlmClient("""
                {
                  "action": "%s",
                  "arguments": {
                    "query": "PAY-123"
                  },
                  "reason": "The question references a Jira issue."
                }
                """.formatted(jira.plannerId()));

        ToolSelectionPlan plan = new LlmMcpToolPlanner(llm)
                .plan(
                        "What happened to PAY-123?",
                        List.of(jira))
                .orElseThrow();

        assertThat(plan.serverId())
                .isEqualTo(serverId);

        assertThat(plan.toolName())
                .isEqualTo("search_jira");

        assertThat(plan.arguments())
                .containsEntry("query", "PAY-123");

        assertThat(plan.reason())
                .isEqualTo("The question references a Jira issue.");
    }

    @Test
    void finishReturnsEmpty() {
        LlmClient llm = new StubLlmClient("""
                {
                  "action": "FINISH",
                  "arguments": {},
                  "reason": "Enough information is already available."
                }
                """);

        assertThat(
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "What happened?",
                                List.of(candidate("search"))))
                .isEmpty();
    }

    @Test
    void unknownToolSelectedByModelIsRejected() {
        LlmClient llm = new StubLlmClient("""
                {
                  "action": "not-allowed",
                  "arguments": {},
                  "reason": "bad selection"
                }
                """);

        assertThatThrownBy(() ->
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of(candidate("search"))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void duplicatePlannerIdsAreRejected() {
        UUID serverId = UUID.randomUUID();

        McpToolCandidate first =
                candidate(serverId, "search");

        McpToolCandidate second =
                candidate(serverId, "search");

        LlmClient llm = new StubLlmClient("""
                {
                  "action": "FINISH",
                  "arguments": {},
                  "reason": "finish"
                }
                """);

        assertThatThrownBy(() ->
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Duplicate MCP planner tool id");
    }

    @Test
    void sameToolNameOnDifferentServersIsAllowed() {
        McpToolCandidate first =
                candidate(UUID.randomUUID(), "search");

        McpToolCandidate second =
                candidate(UUID.randomUUID(), "search");

        LlmClient llm = new StubLlmClient("""
                {
                  "action": "FINISH",
                  "arguments": {},
                  "reason": "finish"
                }
                """);

        assertThat(
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of(first, second)))
                .isEmpty();
    }

    @Test
    void emptyCatalogDoesNotCallModel() {
        RecordingLlmClient llm =
                new RecordingLlmClient();

        assertThat(
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of()))
                .isEmpty();

        assertThat(llm.called)
                .isFalse();
    }

    @Test
    void blankQuestionIsRejected() {
        LlmMcpToolPlanner planner =
                new LlmMcpToolPlanner(
                        new RecordingLlmClient());

        assertThatThrownBy(() ->
                planner.plan(
                        " ",
                        List.of(candidate("search"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "question must not be blank");
    }

    @Test
    void nonObjectArgumentsAreRejected() {
        UUID serverId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001");

        McpToolCandidate candidate =
                candidate(serverId, "search");

        LlmClient llm = new StubLlmClient("""
                {
                  "action": "%s",
                  "arguments": "invalid",
                  "reason": "bad arguments"
                }
                """.formatted(candidate.plannerId()));

        assertThatThrownBy(() ->
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of(candidate)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining(
                        "invalid arguments");
    }
    @Test
    void nullToolInsideCatalogIsRejected() {
        LlmClient llm = new RecordingLlmClient();

        List<McpToolCandidate> tools = new java.util.ArrayList<>();
        tools.add(candidate("search"));
        tools.add(null);

        assertThatThrownBy(() ->
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                tools))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "availableTools must not contain null");
    }

    @Test
    void nullDecisionIsRejected() {
        LlmClient llm = new LlmClient() {

            @Override
            public JsonNode callTool(
                    String system,
                    String user,
                    ToolSpec tool) {
                return null;
            }

            @Override
            public String complete(
                    String system,
                    String user) {
                return "";
            }
        };

        assertThatThrownBy(() ->
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of(candidate("search"))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining(
                        "invalid MCP planning decision");
    }

    @Test
    void nonObjectDecisionIsRejected() {
        LlmClient llm =
                new StubLlmClient("\"invalid\"");

        assertThatThrownBy(() ->
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find something",
                                List.of(candidate("search"))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining(
                        "invalid MCP planning decision");
    }

    @Test
    void nestedArgumentsAreConvertedCorrectly() {
        UUID serverId = UUID.randomUUID();

        McpToolCandidate tool =
                candidate(serverId, "search");

        LlmClient llm = new StubLlmClient("""
                {
                  "action": "%s",
                  "arguments": {
                    "query": "PAY-123",
                    "limit": 10,
                    "includeClosed": true,
                    "filters": {
                      "project": "PAY",
                      "priority": "HIGH"
                    },
                    "statuses": [
                      "OPEN",
                      "IN_PROGRESS"
                    ]
                  },
                  "reason": "The tool can search the issue."
                }
                """.formatted(tool.plannerId()));

        ToolSelectionPlan plan =
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find PAY-123",
                                List.of(tool))
                        .orElseThrow();

        assertThat(plan.arguments())
                .containsEntry("query", "PAY-123")
                .containsEntry("limit", 10L)
                .containsEntry("includeClosed", true);

        assertThat(plan.arguments())
                .containsKey("filters")
                .containsKey("statuses");

        @SuppressWarnings("unchecked")
        Map<String, Object> filters =
                (Map<String, Object>) plan.arguments()
                        .get("filters");

        assertThat(filters)
                .containsEntry("project", "PAY")
                .containsEntry("priority", "HIGH");

        @SuppressWarnings("unchecked")
        List<Object> statuses =
                (List<Object>) plan.arguments()
                        .get("statuses");

        assertThat(statuses)
                .containsExactly(
                        "OPEN",
                        "IN_PROGRESS");
    }

    @Test
    void selectedPlanCanBeConvertedToExecutionRequest() {
        UUID serverId = UUID.randomUUID();

        McpToolCandidate tool =
                candidate(serverId, "search");

        LlmClient llm = new StubLlmClient("""
                {
                  "action": "%s",
                  "arguments": {
                    "query": "PAY-123"
                  },
                  "reason": "Search the issue."
                }
                """.formatted(tool.plannerId()));

        ToolSelectionPlan plan =
                new LlmMcpToolPlanner(llm)
                        .plan(
                                "Find PAY-123",
                                List.of(tool))
                        .orElseThrow();

        var request =
                plan.toExecutionRequest();

        assertThat(request.serverId())
                .isEqualTo(serverId);

        assertThat(request.toolName())
                .isEqualTo("search");

        assertThat(request.arguments())
                .containsEntry("query", "PAY-123");
    }

    private static McpToolCandidate candidate(
            String toolName) {

        return candidate(
                UUID.randomUUID(),
                toolName);
    }

    private static McpToolCandidate candidate(
            UUID serverId,
            String toolName) {

        return new McpToolCandidate(
                serverId,
                toolName,
                toolName,
                "Test tool",
                Map.of(
                        "type",
                        "object"));
    }

    private class StubLlmClient
            implements LlmClient {

        private final String response;

        private StubLlmClient(String response) {
            this.response = response;
        }

        @Override
        public JsonNode callTool(
                String system,
                String user,
                ToolSpec tool) {

            try {
                return mapper.readTree(response);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public String complete(
                String system,
                String user) {

            return "";
        }
    }

    private static class RecordingLlmClient
            implements LlmClient {

        private boolean called;

        @Override
        public JsonNode callTool(
                String system,
                String user,
                ToolSpec tool) {

            called = true;

            throw new AssertionError(
                    "LLM should not have been called");
        }

        @Override
        public String complete(
                String system,
                String user) {

            called = true;

            throw new AssertionError(
                    "LLM should not have been called");
        }
    }
}