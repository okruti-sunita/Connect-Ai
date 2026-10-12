package com.connectai.agent.fastpath;

import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.TemplateGroundedAnswerGenerator;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.agent.ToolExecutionRequest;
import com.connectai.agent.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The tool port is a scripted test double returning GitHub-shaped JSON; no MCP server or model. */
class FastPathEngineTest {

    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID WORK = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Map<String, Object> SEARCH_SCHEMA = Map.of("type", "object", "properties",
            Map.of("query", Map.of("type", "string"), "perPage", Map.of("type", "number")), "required", List.of("query"));
    private static final Map<String, Object> REPO_SCHEMA = Map.of("type", "object", "properties",
            Map.of("owner", Map.of("type", "string"), "repo", Map.of("type", "string"), "perPage", Map.of("type", "number"),
                    "state", Map.of("type", "string", "enum", List.of("open", "closed", "all"))),
            "required", List.of("owner", "repo"));

    private final List<ToolExecutionRequest> calls = new ArrayList<>();

    private static McpToolCandidate tool(UUID server, String name, Map<String, Object> schema, String connection) {
        return new McpToolCandidate(server, name, "", name, schema, true, connection);
    }

    private static List<McpToolCandidate> twoAccounts() {
        return List.of(
                tool(MAIN, "get_me", Map.of(), "GitHub - Main"), tool(MAIN, "search_repositories", SEARCH_SCHEMA, "GitHub - Main"),
                tool(MAIN, "search_pull_requests", SEARCH_SCHEMA, "GitHub - Main"),
                tool(WORK, "get_me", Map.of(), "GitHub - Work"), tool(WORK, "search_repositories", SEARCH_SCHEMA, "GitHub - Work"),
                tool(MAIN, "list_commits", REPO_SCHEMA, "GitHub - Main"), tool(MAIN, "list_pull_requests", REPO_SCHEMA, "GitHub - Main"));
    }

    private FastPathEngine engine(Function<ToolExecutionRequest, String> responder) {
        ToolExecutionPort port = request -> {
            calls.add(request);
            return new ToolExecutionResult(request.toolName(), false,
                    List.of(new ToolExecutionContent("text", responder.apply(request))), null);
        };
        return new FastPathEngine(new PlannedToolExecutor(port), new GroundedAnswerContextBuilder());
    }

    private static final FastPathEngine.AnswerFallback NO_FALLBACK = execution -> {
        throw new AssertionError("fallback must not be needed");
    };

    private static String githubResponse(ToolExecutionRequest request) {
        return switch (request.toolName()) {
            case "get_me" -> request.serverId().equals(MAIN)
                    ? "{\"login\":\"me-main\",\"id\":1,\"details\":{\"name\":\"Main User\",\"public_repos\":2}}"
                    : "{\"login\":\"me-work\",\"id\":2}";
            case "search_repositories" -> request.arguments().get("query").equals("user:me-main")
                    ? "{\"total_count\":2,\"items\":[{\"name\":\"awsdemo1\",\"language\":\"Java\",\"updated_at\":\"2026-10-01T00:00:00Z\"},{\"name\":\"aws-1\"}]}"
                    : "{\"total_count\":0,\"items\":[]}";
            case "search_pull_requests" -> "{\"total_count\":0,\"incomplete_results\":false}";
            case "list_commits" -> "[{\"sha\":\"8cdbcc16547ddf\",\"commit\":{\"message\":\"Fix login timeout\\nmore\",\"author\":{\"name\":\"Ana\",\"date\":\"2026-10-10T08:00:00Z\"}}}]";
            case "list_pull_requests" -> "[{\"number\":12,\"title\":\"Add cache\",\"state\":\"open\",\"user\":{\"login\":\"bob\"}}]";
            default -> "{}";
        };
    }

    @Test
    void myRepositoriesRunsOnEveryConnectionScopedToItsOwnLoginWithoutAModel() {
        var result = engine(FastPathEngineTest::githubResponse)
                .run("Which repositories do I own?", twoAccounts(), step -> { }, NO_FALLBACK).orElseThrow();

        assertThat(calls).extracting(ToolExecutionRequest::toolName)
                .containsExactly("get_me", "search_repositories", "get_me", "search_repositories");
        assertThat(calls.get(1).arguments()).containsEntry("query", "user:me-main").containsEntry("perPage", 100L);
        assertThat(calls.get(3).arguments()).containsEntry("query", "user:me-work");
        GroundedAnswer answer = result.answer();
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.FULL);
        assertThat(answer.answer()).contains("**GitHub - Main**").contains("`awsdemo1`").contains("Java")
                .contains("**GitHub - Work**").contains("owns no repositories").contains("[E2]");
        assertThat(result.execution().status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(result.execution().stopReason()).contains("no AI model call");
        assertThat(result.templateAnswer()).isTrue();
    }

    @Test
    void identityAnswerUsesOnlyReturnedFields() {
        var result = engine(FastPathEngineTest::githubResponse)
                .run("who am i", twoAccounts(), step -> { }, NO_FALLBACK).orElseThrow();

        assertThat(result.answer().answer()).contains("Signed in as **me-main**").contains("Main User")
                .contains("2 public repositories").contains("Signed in as **me-work**").doesNotContain("null");
    }

    @Test
    void emptySearchIsAnsweredAsNone() {
        var result = engine(FastPathEngineTest::githubResponse)
                .run("Which open pull requests are there in my repositories?", twoAccounts(), step -> { }, NO_FALLBACK)
                .orElseThrow();

        assertThat(calls.get(1).arguments()).containsEntry("query", "is:pr is:open user:me-main");
        assertThat(result.answer().answer()).contains("There are no open pull requests in repositories owned by **me-main**");
    }

    @Test
    void repositoryQuestionsPassOwnerRepoStateAndLimit() {
        var commits = engine(FastPathEngineTest::githubResponse)
                .run("last 3 commits in octo/api", twoAccounts(), step -> { }, NO_FALLBACK).orElseThrow();
        assertThat(calls.get(0).arguments()).containsEntry("owner", "octo").containsEntry("repo", "api").containsEntry("perPage", 3L);
        assertThat(commits.answer().answer()).contains("`8cdbcc1` Fix login timeout — Ana, 2026-10-10").doesNotContain("more");

        calls.clear();
        var pulls = engine(FastPathEngineTest::githubResponse)
                .run("closed pull requests in octo/api", twoAccounts(), step -> { }, NO_FALLBACK).orElseThrow();
        assertThat(calls.get(0).arguments()).containsEntry("state", "closed");
        assertThat(pulls.answer().answer()).contains("#12 Add cache");
    }

    @Test
    void filterTheToolDoesNotHaveGoesToTheAgent() {
        assertThat(engine(FastPathEngineTest::githubResponse)
                .run("merged pull requests in octo/api", twoAccounts(), step -> { }, NO_FALLBACK)).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void unrecognisedQuestionsAndMissingToolsGoToTheAgent() {
        FastPathEngine engine = engine(FastPathEngineTest::githubResponse);
        assertThat(engine.run("Why did the build break?", twoAccounts(), step -> { }, NO_FALLBACK)).isEmpty();
        assertThat(engine.run("branches in octo/api", twoAccounts(), step -> { }, NO_FALLBACK)).isEmpty(); // no list_branches
        assertThat(calls).isEmpty();
    }

    @Test
    void unreadableOutputFallsBackToTheAnswerStep() {
        List<AgentExecutionStep> progress = new ArrayList<>();
        var result = engine(request -> "plain text, not JSON")
                .run("last 3 commits in octo/api", twoAccounts(), progress::add,
                        execution -> new TemplateGroundedAnswerGenerator(new GroundedAnswerContextBuilder()).generate(execution))
                .orElseThrow();

        assertThat(result.templateAnswer()).isFalse();
        assertThat(result.answer().fallbackUsed()).isTrue();
        assertThat(progress).hasSize(1);
    }
}
