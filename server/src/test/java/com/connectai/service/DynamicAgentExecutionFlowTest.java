package com.connectai.service;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.GroundedEvidence;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.TemplateGroundedAnswerGenerator;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.agent.ToolExecutionRequest;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSemanticRetriever;
import com.connectai.domain.InvestigationStatus;
import com.connectai.llm.LlmClient;
import com.connectai.llm.LlmMcpToolPlanner;
import com.connectai.llm.LlmException;
import com.connectai.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies planner decisions reach the application execution port without a live LLM or MCP server. */
class DynamicAgentExecutionFlowTest {

    private static final UUID SERVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String QUESTION = "Find Jira issue PAY-123 status";
    private static final McpToolCandidate JIRA_TOOL = new McpToolCandidate(
            SERVER_ID,
            "search_jira",
            "Search Jira",
            "Search Jira issues by query",
            Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")),
            true,
            "Jira Work");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectedPlannerActionInvokesExpectedToolAndPreservesItsResultAsEvidence() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(mapper,
                """
                        {"action":"T1","arguments":{"query":"PAY-123"},"reason":"Look up the referenced issue."}
                        """,
                """
                        {"action":"FINISH","arguments":{},"reason":"The issue result is available."}
                        """);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        ToolExecutionPort port = request -> {
            requests.add(request);
            return new ToolExecutionResult(
                    request.toolName(),
                    false,
                    List.of(new ToolExecutionContent("text", "PAY-123 is OPEN.")),
                    Map.of("status", "OPEN"));
        };
        ControlledAgentExecutor executor = executor(llm, port);

        AgentExecutionState execution = executor.execute(QUESTION, List.of(JIRA_TOOL));
        List<GroundedEvidence> evidence = new GroundedAnswerContextBuilder().build(execution).evidence();

        assertThat(requests).containsExactly(new ToolExecutionRequest(
                SERVER_ID, "search_jira", Map.of("query", "PAY-123")));
        assertThat(execution.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(execution.steps()).hasSize(1);
        assertThat(execution.steps().get(0).status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(execution.steps().get(0).result().content())
                .containsExactly(new ToolExecutionContent("text", "PAY-123 is OPEN."));
        assertThat(execution.steps().get(0).result().structuredContent()).isEqualTo(Map.of("status", "OPEN"));
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).content()).contains("PAY-123 is OPEN.").contains("status=OPEN");
        assertThat(llm.calls).isEqualTo(2);
    }

    @Test
    void semanticOnlyRouteExecutesExactArgumentsAndProducesGroundedAnswer() throws Exception {
        McpToolCandidate semanticTool = new McpToolCandidate(SERVER_ID, "query_ticket_context", "Ticket context",
                "Retrieve historical context for a support case", issueSearchSchema(), true, "Jira Work");
        ToolSemanticRetriever acceptedSemanticResult = (question, liveTools, limit) -> List.of(semanticTool);
        ScriptedLlm llm = new ScriptedLlm(mapper,
                """
                        {"action":"T1","arguments":{"query":"PAY-123"},"reason":"Retrieve the case context."}
                        """,
                """
                        {"action":"FINISH","arguments":{},"reason":"The returned case context answers the question."}
                        """);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        ToolExecutionPort port = request -> {
            requests.add(request);
            return new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "PAY-123 was escalated after a database timeout.")),
                    Map.of("status", "ESCALATED", "cause", "database timeout"));
        };
        ControlledAgentExecutor executor = new ControlledAgentExecutor(
                new LlmMcpToolPlanner(llm, 6, 2, acceptedSemanticResult),
                new PlannedToolExecutor(port), 4, Duration.ofMinutes(1), 2);

        AgentExecutionState execution = executor.execute("Why was PAY-123 escalated?", List.of(semanticTool));
        GroundedAnswer answer = new TemplateGroundedAnswerGenerator(new GroundedAnswerContextBuilder())
                .generate(execution);

        assertThat(requests).containsExactly(new ToolExecutionRequest(
                SERVER_ID, "query_ticket_context", Map.of("query", "PAY-123")));
        assertThat(execution.steps()).singleElement().satisfies(step ->
                assertThat(step.result().structuredContent()).isEqualTo(
                        Map.of("status", "ESCALATED", "cause", "database timeout")));
        assertThat(answer.evidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.content()).contains("database timeout", "ESCALATED"));
        assertThat(answer.answer()).contains("database timeout", "[E1]");
    }

    @Test
    void providerConstraintScopesCatalogBeforeSemanticRetrieval() {
        McpToolCandidate slack = new McpToolCandidate(SERVER_ID, "search_messages", "Slack messages",
                "Search Slack messages", issueSearchSchema(), true, "Slack - Workspace", "slack");
        McpToolCandidate linear = new McpToolCandidate(UUID.randomUUID(), "search_issues", "Linear issues",
                "Search Linear issues", issueSearchSchema(), true, "Linear - Workspace", "linear");
        List<List<McpToolCandidate>> retrievedCatalogs = new ArrayList<>();
        ToolSemanticRetriever semantic = (question, liveTools, limit) -> {
            retrievedCatalogs.add(List.copyOf(liveTools));
            return List.of(linear); // A foreign result cannot escape the provider-scoped live catalog.
        };
        ScriptedLlm llm = new ScriptedLlm(mapper,
                """
                        {"action":"T1","arguments":{"query":"PAY-123"},"reason":"Search Slack."}
                        """);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        ToolExecutionPort port = request -> {
            requests.add(request);
            return new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "Slack message PAY-123 found.")), Map.of());
        };
        ControlledAgentExecutor executor = new ControlledAgentExecutor(
                new LlmMcpToolPlanner(llm, 6, 1, semantic), new PlannedToolExecutor(port),
                2, Duration.ofMinutes(1), 1);

        AgentExecutionState result = executor.execute("Search Slack messages for PAY-123", List.of(slack, linear));

        assertThat(retrievedCatalogs).isNotEmpty().allSatisfy(catalog -> assertThat(catalog).containsExactly(slack));
        assertThat(requests).containsExactly(new ToolExecutionRequest(
                SERVER_ID, "search_messages", Map.of("query", "PAY-123")));
        assertThat(result.steps()).singleElement().satisfies(step ->
                assertThat(step.plan().serverId()).isEqualTo(SERVER_ID));
    }

    @Test
    void finishBeforeAnyEvidenceCompletesExecutionButCannotProduceAnsweredInvestigation() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(mapper,
                """
                        {"action":"FINISH","arguments":{},"reason":"No evidence was gathered."}
                        """);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        ToolExecutionPort port = request -> {
            requests.add(request);
            return new ToolExecutionResult(request.toolName(), false, List.of(), null);
        };

        AgentExecutionState execution = executor(llm, port).execute(QUESTION, List.of(JIRA_TOOL));
        GroundedAnswer answer = new TemplateGroundedAnswerGenerator(new GroundedAnswerContextBuilder())
                .generate(execution);

        assertThat(requests).isEmpty();
        assertThat(execution.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(execution.steps()).isEmpty();
        assertThat(answer.evidence()).isEmpty();
        assertThat(answer.grounded()).isFalse();
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.NONE);
        assertThat(InvestigationStore.statusFor(execution, answer)).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(InvestigationStore.statusFor(execution, answer)).isNotEqualTo(InvestigationStatus.ANSWERED);
    }

    @Test
    void multiServerMultiStepPlanDispatchesExactToolsAndNextInvestigationUsesItsCurrentCatalog() throws Exception {
        UUID githubServer = UUID.fromString("22222222-2222-2222-2222-222222222222");
        McpToolCandidate github = new McpToolCandidate(githubServer, "search_github_issues", "GitHub issues",
                "Search GitHub issues by query", issueSearchSchema(), true, "GitHub");
        ScriptedLlm llm = new ScriptedLlm(mapper,
                """
                        {"action":"T2","arguments":{"query":"repo:octo/demo #18"},"reason":"Find the GitHub issue."}
                        """,
                """
                        {"action":"T1","arguments":{"query":"PAY-123"},"reason":"Find the Jira issue."}
                        """,
                """
                        {"action":"FINISH","arguments":{},"reason":"Both requested issue records were retrieved."}
                        """,
                """
                        {"action":"T1","arguments":{"query":"PAY-777"},"reason":"Search the current investigation catalog."}
                        """,
                """
                        {"action":"FINISH","arguments":{},"reason":"The issue record was retrieved."}
                        """
        );
        List<ToolExecutionRequest> requests = new ArrayList<>();
        ToolExecutionPort port = request -> {
            requests.add(request);
            return new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", "Evidence from " + request.toolName()
                            + " for " + request.arguments().get("query"))),
                    Map.of("query", request.arguments().get("query")));
        };
        ControlledAgentExecutor executor = executor(llm, port);
        McpToolCandidate jira = new McpToolCandidate(SERVER_ID, "search_jira", "Jira issues",
                "Search Jira issues by query", issueSearchSchema(), true, "Jira Work");

        String combinedQuestion = "Find Jira issue PAY-123 and GitHub issue #18";
        AgentExecutionState combined = executor.execute(combinedQuestion, List.of(jira, github));
        assertThat(requests.subList(0, 2)).containsExactly(
                new ToolExecutionRequest(githubServer, "search_github_issues", Map.of("query", "repo:octo/demo #18")),
                new ToolExecutionRequest(SERVER_ID, "search_jira", Map.of("query", "PAY-123")));
        assertThat(combined.steps()).hasSize(2);
        assertThat(new GroundedAnswerContextBuilder().build(combined).evidence()).hasSize(2);

        // A distinct investigation receives only its current catalog; the previous run's state is not reused.
        AgentExecutionState next = executor.execute("Find Jira issue PAY-777", List.of(jira));
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2)).isEqualTo(new ToolExecutionRequest(
                SERVER_ID, "search_jira", Map.of("query", "PAY-777")));
        assertThat(next.steps()).hasSize(1);
        assertThat(next.steps().get(0).result().structuredContent()).isEqualTo(Map.of("query", "PAY-777"));
    }

    private static Map<String, Object> issueSearchSchema() {
        return Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query"));
    }

    private ControlledAgentExecutor executor(LlmClient llm, ToolExecutionPort port) {
        return new ControlledAgentExecutor(
                new LlmMcpToolPlanner(llm, 6, 2),
                new PlannedToolExecutor(port),
                4,
                Duration.ofMinutes(1),
                2);
    }

    private static final class ScriptedLlm implements LlmClient {
        private final ObjectMapper mapper;
        private final Deque<String> replies;
        private int calls;

        private ScriptedLlm(ObjectMapper mapper, String... replies) {
            this.mapper = mapper;
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public JsonNode callTool(String system, String user, ToolSpec tool) {
            calls++;
            String reply = replies.poll();
            if (reply == null) throw new LlmException("Scripted LLM has no remaining reply.");
            try {
                return mapper.readTree(reply);
            } catch (Exception exception) {
                throw new LlmException("Invalid scripted LLM reply.", exception);
            }
        }

        @Override
        public String complete(String system, String user) {
            throw new UnsupportedOperationException("This test only exercises structured planner calls.");
        }
    }
}
