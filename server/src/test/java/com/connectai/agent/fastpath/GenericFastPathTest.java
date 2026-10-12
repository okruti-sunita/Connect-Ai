package com.connectai.agent.fastpath;

import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionRequest;
import com.connectai.agent.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic fast path against catalogs shaped like Jira, Slack and Linear MCP servers (tool
 * names, schemas and JSON are test doubles, not real servers). Nothing here is GitHub-specific.
 */
class GenericFastPathTest {

    private static final UUID JIRA = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID SLACK = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID LINEAR = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private final List<ToolExecutionRequest> calls = new ArrayList<>();

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    private static McpToolCandidate tool(UUID server, String name, String description, Map<String, Object> schema, String connection) {
        return new McpToolCandidate(server, name, "", description, schema, true, connection);
    }

    private static final List<McpToolCandidate> JIRA_TOOLS = List.of(
            tool(JIRA, "myself", "Returns details for the current user.", schema(Map.of()), "Jira"),
            tool(JIRA, "list_projects", "List Jira projects.", schema(Map.of("maxResults", Map.of("type", "integer"))), "Jira"),
            tool(JIRA, "get_issue", "Get a Jira issue by key.", schema(Map.of("issueKey", Map.of("type", "string")), "issueKey"), "Jira"),
            tool(JIRA, "search_issues", "Search issues with JQL.", schema(Map.of("jql", Map.of("type", "string")), "jql"), "Jira"));

    private static final List<McpToolCandidate> SLACK_TOOLS = List.of(
            tool(SLACK, "list_channels", "List public channels.", schema(Map.of("limit", Map.of("type", "integer"))), "Slack"),
            tool(SLACK, "list_messages", "List messages in a channel.",
                    schema(Map.of("channel", Map.of("type", "string"), "limit", Map.of("type", "integer")), "channel"), "Slack"));

    private static final List<McpToolCandidate> LINEAR_TOOLS = List.of(
            tool(LINEAR, "get_viewer", "Get the current user (viewer).", schema(Map.of()), "Linear"),
            tool(LINEAR, "list_issues", "List issues.", schema(Map.of(
                    "assigneeId", Map.of("type", "string"),
                    "state", Map.of("type", "string", "enum", List.of("open", "closed")))), "Linear"),
            tool(LINEAR, "list_comments", "List comments of an issue.",
                    schema(Map.of("issueNumber", Map.of("type", "integer")), "issueNumber"), "Linear"));

    private Optional<FastPathEngine.Result> ask(String question, List<McpToolCandidate> tools, Function<ToolExecutionRequest, String> json) {
        FastPathEngine engine = new FastPathEngine(new PlannedToolExecutor(request -> {
            calls.add(request);
            return new ToolExecutionResult(request.toolName(), false, List.of(new ToolExecutionContent("text", json.apply(request))), null);
        }), new GroundedAnswerContextBuilder());
        return engine.run(question, tools, step -> { }, execution -> {
            throw new AssertionError("template should answer");
        });
    }

    private static String jira(ToolExecutionRequest r) {
        return switch (r.toolName()) {
            case "myself" -> "{\"accountId\":\"5b10a2\",\"displayName\":\"Ana Lima\",\"emailAddress\":\"ana@example.com\"}";
            case "list_projects" -> "[{\"key\":\"PAY\",\"name\":\"Payments\"},{\"key\":\"WEB\",\"name\":\"Website\"}]";
            case "get_issue" -> "{\"key\":\"PAY-123\",\"fields\":{\"summary\":\"Card payments time out\",\"status\":{\"name\":\"In Progress\"},"
                    + "\"assignee\":{\"displayName\":\"Ana Lima\"},\"updated\":\"2026-10-11T09:00:00Z\"}}";
            default -> "{}";
        };
    }

    @Test
    void identityWorksWithAnyIdentityTool() {
        var result = ask("who am i", JIRA_TOOLS, GenericFastPathTest::jira).orElseThrow();

        assertThat(calls).extracting(ToolExecutionRequest::toolName).containsExactly("myself");
        assertThat(result.answer().answer()).contains("Signed in as **Ana Lima**").contains("ana@example.com");
        assertThat(result.intent()).isEqualTo("GENERIC_IDENTITY");
    }

    @Test
    void listsAnyEntityByToolName() {
        var result = ask("list projects", JIRA_TOOLS, GenericFastPathTest::jira).orElseThrow();

        assertThat(calls).extracting(ToolExecutionRequest::toolName).containsExactly("list_projects");
        assertThat(result.answer().answer()).contains("2 projects").contains("`PAY` Payments").contains("`WEB` Website");
        assertThat(result.answer().coverage()).isEqualTo(GroundedAnswer.Coverage.FULL);
    }

    @Test
    void getsOneItemByKeyIncludingJiraStyleFields() {
        var result = ask("what is the status of ticket PAY-123", JIRA_TOOLS, GenericFastPathTest::jira).orElseThrow();

        assertThat(calls.get(0).toolName()).isEqualTo("get_issue");
        assertThat(calls.get(0).arguments()).containsEntry("issueKey", "PAY-123");
        assertThat(result.answer().answer()).contains("`PAY-123` Card payments time out")
                .contains("In Progress").contains("Ana Lima").contains("2026-10-11");
    }

    @Test
    void bareKeyIsEnough() {
        assertThat(ask("show PAY-123", JIRA_TOOLS, GenericFastPathTest::jira)).isPresent();
        assertThat(calls.get(0).toolName()).isEqualTo("get_issue");
    }

    @Test
    void channelValuesFillChannelParameters() {
        var result = ask("show the last 5 messages in #general", SLACK_TOOLS,
                r -> "[{\"text\":\"Deploy finished\",\"user\":\"U1\",\"ts\":\"1728720000\"}]").orElseThrow();

        assertThat(calls.get(0).toolName()).isEqualTo("list_messages");
        assertThat(calls.get(0).arguments()).containsEntry("channel", "general").containsEntry("limit", 5L);
        assertThat(result.answer().answer()).contains("in #general").contains("Deploy finished");
    }

    @Test
    void myItemsAreScopedThroughTheIdentityToolsId() {
        var result = ask("show my open issues", LINEAR_TOOLS, r -> r.toolName().equals("get_viewer")
                ? "{\"id\":\"user-7\",\"name\":\"Ana\"}"
                : "[{\"number\":42,\"title\":\"Fix login\",\"state\":\"open\"}]").orElseThrow();

        assertThat(calls).extracting(ToolExecutionRequest::toolName).containsExactly("get_viewer", "list_issues");
        assertThat(calls.get(1).arguments()).containsEntry("assigneeId", "user-7").containsEntry("state", "open");
        assertThat(result.answer().answer()).contains("#42 Fix login");
    }

    @Test
    void uncertainCasesGoToTheAgent() {
        // "my open issues" on Jira: only a JQL search exists, which cannot be filled without the model
        assertThat(ask("show my open issues", JIRA_TOOLS, GenericFastPathTest::jira)).isEmpty();
        // required parameter (issue number) not in the question
        assertThat(ask("list comments", LINEAR_TOOLS, r -> "[]")).isEmpty();
        // a requested filter the tool does not have
        assertThat(ask("list closed projects", JIRA_TOOLS, GenericFastPathTest::jira)).isEmpty();
        // reasoning
        assertThat(ask("why are payments failing", JIRA_TOOLS, GenericFastPathTest::jira)).isEmpty();
        // two different tools fit equally
        List<McpToolCandidate> twoFits = List.of(
                tool(JIRA, "list_tickets", "List tickets.", schema(Map.of()), "A"),
                tool(LINEAR, "list_issues", "List issues.", schema(Map.of()), "B"));
        assertThat(ask("list tickets", twoFits, r -> "[]")).isPresent(); // exact word "tickets" wins over the synonym
        assertThat(ask("list bugs", twoFits, r -> "[]")).isEmpty();      // only synonyms: a tie, so no guess
        assertThat(calls).extracting(ToolExecutionRequest::toolName).containsExactly("list_tickets");
    }

    /** Reported case: collaborators rendered as "#91866544" instead of their login. */
    @Test
    void peopleAreLabelledByLoginNotNumericId() {
        List<McpToolCandidate> tools = List.of(tool(JIRA, "list_repository_collaborators", "List collaborators.",
                schema(Map.of("owner", Map.of("type", "string"), "repo", Map.of("type", "string")), "owner", "repo"), "GitHub"));
        var result = ask("list the collaborators of octo/api", tools,
                r -> "[{\"login\":\"okruti-raghuraj\",\"id\":91866544}]").orElseThrow();

        assertThat(result.answer().answer()).contains("- okruti-raghuraj").doesNotContain("91866544");
    }

    /** Reported case: get_teams returns teams grouped by organisation; the AI miscounted them. */
    @Test
    void groupedResultsAreFlattenedAndCountedExactly() {
        List<McpToolCandidate> tools = List.of(tool(JIRA, "list_teams", "List teams.", schema(Map.of()), "GitHub"));
        var result = ask("list teams", tools, r -> "[{\"org\":\"acme\",\"teams\":[{\"name\":\"HRMS\"},{\"name\":\"IAM\"}]},"
                + "{\"org\":\"beta\",\"teams\":[{\"name\":\"Fusion5\"}]}]").orElseThrow();

        assertThat(result.answer().answer()).startsWith("3 teams").contains("- HRMS — acme").contains("- Fusion5 — beta");
    }

    /** The live "list my teams" flow: identity per connection, the first connection's call fails. */
    @Test
    void myGroupedTeamsWithOneFailedConnection() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
        Map<String, Object> teamsSchema = schema(Map.of("user", Map.of("type", "string")));
        List<McpToolCandidate> tools = List.of(
                tool(JIRA, "get_me", "Get details of the authenticated GitHub user.", schema(Map.of()), "GitHub - Main"),
                tool(JIRA, "get_teams", "Get details of the teams the user is a member of.", teamsSchema, "GitHub - Main"),
                tool(other, "get_me", "Get details of the authenticated GitHub user.", schema(Map.of()), "GitHub - 2"),
                tool(other, "get_teams", "Get details of the teams the user is a member of.", teamsSchema, "GitHub - 2"));
        FastPathEngine engine = new FastPathEngine(new PlannedToolExecutor(request -> {
            if (request.toolName().equals("get_teams") && request.serverId().equals(JIRA)) {
                return new ToolExecutionResult("get_teams", true, List.of(new ToolExecutionContent("text", "missing scope")), null);
            }
            String json = request.toolName().equals("get_me") ? "{\"login\":\"octo\"}"
                    : "[{\"org\":\"acme\",\"teams\":[{\"name\":\"HRMS\",\"slug\":\"hrms\",\"description\":\"People\"},"
                    + "{\"name\":\"IAM\",\"slug\":\"iam\",\"description\":\"\"}]}]";
            return new ToolExecutionResult(request.toolName(), false, List.of(new ToolExecutionContent("text", json)), null);
        }), new GroundedAnswerContextBuilder());

        var result = engine.run("list my teams", tools, step -> { }, execution -> {
            throw new AssertionError("template should answer");
        }).orElseThrow();

        assertThat(result.answer().answer()).contains("HRMS").contains("get_teams failed");
        assertThat(result.answer().coverage()).isEqualTo(GroundedAnswer.Coverage.PARTIAL);
    }

    @Test
    void emptyListIsANone() {
        var result = ask("list channels", SLACK_TOOLS, r -> "[]").orElseThrow();
        assertThat(result.answer().answer()).startsWith("No channels found");
    }
}
