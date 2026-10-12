package com.connectai.mcp;

import com.connectai.mcp.client.McpConnectionService;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue analysis end to end over a REAL MCP connection (official SDK, Streamable HTTP). The server is
 * a TEST FIXTURE: its tools have the shapes of mcp-atlassian's Jira tools (jira_get_issue, jira_search)
 * and GitHub's MCP tools (get_me, search_commits, search_pull_requests, list_commits, get_commit), with
 * canned data. It proves the pipeline - tool discovery by capability, the calls made, scoring and the
 * cited report - not anything about a real Jira site.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "connectai.tools.mode=mcp",
                "connectai.llm.enabled=false",
                "connectai.investigations.async=false",
                "connectai.seed-demo-data=false"
        })
@Import(IssueAnalysisIntegrationTest.FixtureServer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueAnalysisIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    McpServerRepository servers;

    @Autowired
    McpConnectionService connections;

    @BeforeAll
    void connect() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Fixture Jira+GitHub");
        body.put("transport", "STREAMABLE_HTTP");
        body.put("endpoint", "http://localhost:" + port + "/fixture-mcp");
        body.put("authType", "NONE");
        body.put("enabled", true);
        String id = http.exchange("/api/tools", HttpMethod.POST, new HttpEntity<>(body), MAP).getBody().get("id").toString();
        assertThat(http.exchange("/api/tools/" + id + "/connect", HttpMethod.POST, null, MAP).getBody())
                .containsEntry("status", "CONNECTED");
    }

    @Test
    void ranksTheChangeBehindAnIssueAndEstimatesTimeToResolve() {
        CALLS.clear();
        Map<String, Object> result = http.exchange("/api/investigations", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "What caused PAY-12 and how long will it take to fix?")), MAP).getBody();

        assertThat(result).containsEntry("route", "ANALYSIS");
        String answer = result.get("resolvedAnswer").toString();

        // The issue and its clues.
        assertThat(answer).contains("**PAY-12** · Checkout fails with NullPointerException")
                .contains("`PaymentService.java`").contains("`NullPointerException`");
        // The commit that touched the named file just before the issue ranks first, with its reasons.
        assertThat(answer).contains("**Most likely cause** (confidence: high)")
                .contains("1. Commit `a1b2c3d` in `acme/shop`: \"Refactor PaymentService card handling\"")
                .contains("changed `src/main/java/shop/PaymentService.java`, named in the issue")
                .contains("which the fix for PAY-12 also changes")
                .contains("landed 5 h before the issue was raised");
        assertThat(answer).as("a change with only timing in its favour is not named").doesNotContain("e5f6a7b");
        // Changes that mention the key but came after the issue are the response to it, not the cause.
        assertThat(answer).contains("Work already linked to PAY-12").contains("Pull request #7").contains("9f8e7d6");
        // Estimate from resolved history: same type and priority (10 h, 30 h, 72 h) -> median 30 h.
        assertThat(answer).contains("Estimated time to resolve:** about 1.3 days")
                .contains("median of 3 resolved Bug issue(s) in PAY with priority High");
        // Every section cites evidence.
        assertThat(answer).containsPattern("\\[E\\d+]");

        assertThat(CALLS).contains("jira_get_issue", "get_me", "search_commits", "search_pull_requests",
                "list_commits", "get_commit", "jira_search");
        assertThat(result.get("routeDetail").toString()).contains("issue analysis of PAY-12").contains("no AI model call");
        assertThat(result.get("status")).as("high confidence plus an estimate").isEqualTo("ANSWERED");
    }

    @Test
    void anyAnalysisWordWithAnIssueKeyIsRoutedToTheAnalysis() {
        Map<String, Object> result = http.exchange("/api/investigations", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "analyze PAY-404")), MAP).getBody();
        assertThat(result).containsEntry("route", "ANALYSIS");
        assertThat(result.get("resolvedAnswer").toString()).contains("**PAY-404**");
    }

    @Test
    void questionsWithoutAnIssueKeyAreNotAnalysed() {
        Map<String, Object> result = http.exchange("/api/investigations", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "why do checkouts fail")), MAP).getBody();
        assertThat(result.get("route")).isNotEqualTo("ANALYSIS");
    }

    @AfterAll
    void disconnect() {
        servers.findAll().forEach(s -> {
            try {
                connections.disconnect(s.getId());
            } catch (RuntimeException ignored) {
                // best effort
            }
        });
    }

    /** TEST FIXTURE: an MCP server with Jira- and GitHub-shaped tools and canned data. */
    @TestConfiguration
    static class FixtureServer {

        @Bean
        HttpServletStreamableServerTransportProvider fixtureTransport() {
            return HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/fixture-mcp").build();
        }

        @Bean
        ServletRegistrationBean<Servlet> fixtureServlet(HttpServletStreamableServerTransportProvider transport) {
            ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(transport, "/fixture-mcp");
            registration.setName("fixtureMcpServlet");
            return registration;
        }

        private static SyncToolSpecification tool(String name, Map<String, Object> properties, List<String> required,
                                                  Function<Map<String, Object>, String> handler) {
            return SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder(name, Map.of("type", "object", "properties", properties, "required", required))
                            .description(name.equals("get_me") ? "Get details of the authenticated GitHub user" : name).build())
                    .callHandler((exchange, request) -> {
                        CALLS.add(name);
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(handler.apply(request.arguments())))).build();
                    })
                    .build();
        }

        private static final Map<String, Object> STR = Map.of("type", "string");
        private static final Map<String, Object> INT = Map.of("type", "integer");

        private static String commit(String sha, String message, String date) {
            return "{\"sha\":\"" + sha + "\",\"html_url\":\"https://github.com/acme/shop/commit/" + sha + "\","
                    + "\"commit\":{\"message\":\"" + message + "\",\"author\":{\"name\":\"Dev\",\"date\":\"" + date + "\"},"
                    + "\"committer\":{\"name\":\"Dev\",\"date\":\"" + date + "\"}},\"author\":{\"login\":\"dev1\"}}";
        }

        private static String resolved(String key, String created, String resolved, String priority) {
            return "{\"key\":\"" + key + "\",\"created\":\"" + created + "\",\"resolutiondate\":\"" + resolved
                    + "\",\"priority\":{\"name\":\"" + priority + "\"},\"issue_type\":{\"name\":\"Bug\"}}";
        }

        @Bean(destroyMethod = "close")
        McpSyncServer fixtureMcpServer(HttpServletStreamableServerTransportProvider transport) {
            var getIssue = tool("jira_get_issue", Map.of("issue_key", STR, "fields", STR, "comment_limit", INT), List.of("issue_key"),
                    args -> "{\"key\":\"" + args.get("issue_key") + "\",\"summary\":\"Checkout fails with NullPointerException\","
                            + "\"description\":\"Since this morning checkout throws NullPointerException in PaymentService.java "
                            + "when the card token is missing.\",\"status\":{\"name\":\"Open\"},\"issue_type\":{\"name\":\"Bug\"},"
                            + "\"priority\":{\"name\":\"High\"},\"project\":{\"key\":\"PAY\"},"
                            + "\"created\":\"2026-10-10T12:00:00.000+0000\"}");
            var search = tool("jira_search", Map.of("jql", STR, "fields", STR, "limit", INT), List.of("jql"),
                    args -> "{\"total\":4,\"issues\":["
                            + resolved("PAY-3", "2026-09-01T08:00:00.000+0000", "2026-09-01T18:00:00.000+0000", "High") + ","
                            + resolved("PAY-5", "2026-09-03T08:00:00.000+0000", "2026-09-04T14:00:00.000+0000", "High") + ","
                            + resolved("PAY-7", "2026-09-05T08:00:00.000+0000", "2026-09-08T08:00:00.000+0000", "High") + ","
                            + resolved("PAY-8", "2026-09-06T08:00:00.000+0000", "2026-09-07T08:00:00.000+0000", "Low") + "]}");
            var me = tool("get_me", Map.of(), List.of(), args -> "{\"login\":\"dev1\"}");
            var searchCommits = tool("search_commits", Map.of("query", STR, "perPage", INT), List.of("query"),
                    args -> "{\"total_count\":1,\"items\":[" + commit("9f8e7d6c5b4a", "PAY-12: guard missing card token",
                            "2026-10-10T15:00:00Z").replace("\"html_url\"", "\"repository\":{\"full_name\":\"acme/shop\"},\"html_url\"") + "]}");
            var searchPulls = tool("search_pull_requests", Map.of("query", STR, "perPage", INT), List.of("query"),
                    args -> "{\"total_count\":1,\"items\":[{\"number\":7,\"title\":\"Fix PAY-12 NPE at checkout\","
                            + "\"html_url\":\"https://github.com/acme/shop/pull/7\",\"state\":\"open\","
                            + "\"created_at\":\"2026-10-10T16:00:00Z\",\"user\":{\"login\":\"dev2\"}}]}");
            var repos = tool("search_repositories", Map.of("query", STR, "perPage", INT), List.of("query"),
                    args -> "{\"items\":[{\"full_name\":\"acme/shop\"}]}");
            var listCommits = tool("list_commits", Map.of("owner", STR, "repo", STR, "since", STR, "until", STR, "perPage", INT),
                    List.of("owner", "repo"),
                    args -> "[" + commit("a1b2c3d4e5f6", "Refactor PaymentService card handling", "2026-10-10T07:00:00Z") + ","
                            + commit("e5f6a7b8c9d0", "Update README", "2026-10-09T06:00:00Z") + "]");
            var getCommit = tool("get_commit", Map.of("owner", STR, "repo", STR, "sha", STR), List.of("owner", "repo", "sha"),
                    args -> String.valueOf(args.get("sha")).startsWith("9f8e")
                            ? "{\"sha\":\"9f8e7d6c5b4a\",\"stats\":{\"total\":6},\"files\":[{\"filename\":\"src/main/java/shop/PaymentService.java\"}]}"
                            : String.valueOf(args.get("sha")).startsWith("a1b2")
                            ? "{\"sha\":\"a1b2c3d4e5f6\",\"stats\":{\"total\":42},\"files\":[{\"filename\":\"src/main/java/shop/PaymentService.java\"}]}"
                            : "{\"sha\":\"" + args.get("sha") + "\",\"stats\":{\"total\":3},\"files\":[{\"filename\":\"README.md\"}]}");
            return McpServer.sync(transport)
                    .serverInfo("fixture-jira-github", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(getIssue, search, me, searchCommits, searchPulls, repos, listCommits, getCommit)
                    .build();
        }
    }
}
