package com.connectai.mcp;

import com.connectai.llm.LlmClient;
import com.connectai.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Full investigation HTTP path with scripted LLM replies and a local real-protocol MCP server. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "connectai.tools.mode=mcp",
                "connectai.llm.enabled=true",
                "connectai.llm.provider=anthropic",
                "connectai.llm.api-key=integration-test-dummy-key",
                "connectai.investigations.async=false",
                "connectai.seed-demo-data=false",
                "server.error.include-message=always"
        })
@Import(GroundedInvestigationPipelineIntegrationTest.TestFixtures.class)
class GroundedInvestigationPipelineIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RAW_GITHUB_RESULT = """
            {"total_count":1,"incomplete_results":false,"items":[{"id":1394910770,"name":"Connect-Ai",
            "full_name":"okruti-sunita/Connect-Ai","html_url":"https://github.com/okruti-sunita/Connect-Ai",
            "language":"Java","stargazers_count":0,"forks_count":0,"open_issues_count":0,
            "updated_at":"2026-10-12T12:59:11Z","created_at":"2026-09-29T10:18:55Z","private":false,
            "fork":false,"archived":false,"default_branch":"main"}]}
            """.replaceAll("\\s+", "");
    private static final String GROUNDED_ANSWER = """
            {"answer":"The search found one repository, `okruti-sunita/Connect-Ai`, written in Java. Its default branch is `main`; it has 0 stars, 0 forks, and 0 open issues.",
            "coverage":"full","missing":"","citations":["E1"]}
            """;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ScriptedLlm llm;

    @Autowired
    ToolCallCounter toolCalls;

    private String registeredServerId;

    @Test
    void investigationUsesRealMcpPathAndPersistsGroundedHumanAnswerAndRawEvidence() throws Exception {
        Map<String, Object> registration = Map.of(
                "name", "scripted-github",
                "transport", "STREAMABLE_HTTP",
                "endpoint", "http://localhost:" + port + "/mcp",
                "authType", "NONE",
                "enabled", true);
        ResponseEntity<Map<String, Object>> created = post("/api/tools", registration);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String serverId = created.getBody().get("id").toString();
        registeredServerId = serverId;

        ResponseEntity<Map<String, Object>> connected = post("/api/tools/" + serverId + "/connect", null);
        assertThat(connected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(connected.getBody()).containsEntry("initialized", true);

        ResponseEntity<Map<String, Object>> started = post("/api/investigations", Map.of(
                "question", "Summarize the GitHub repository Connect-Ai owned by okruti-sunita"));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> investigation = started.getBody();

        assertThat(investigation).containsEntry("status", "ANSWERED");
        assertThat(investigation.get("resolvedAnswer").toString())
                .contains("one repository", "okruti-sunita/Connect-Ai", "Java", "main", "0 stars", "[E1]")
                .doesNotContain("{\"total_count\"");
        List<Map<String, Object>> steps = list(investigation.get("steps"));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("toolName", "search_repositories")
                .containsEntry("serverId", serverId).containsEntry("status", "SUCCESS");
        assertThat(steps.get(0).get("argumentsJson").toString()).contains("user:okruti-sunita");

        List<Map<String, Object>> evidence = list(investigation.get("evidence"));
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0)).containsEntry("toolName", "search_repositories").containsEntry("sourceRef", "E1");
        assertThat(evidence.get(0).get("summary").toString()).contains("Connect-Ai", "stargazers_count", "default_branch");
        assertThat(toolCalls.calls()).hasValue(1);
        assertThat(llm.toolCalls).containsExactly("choose_mcp_tool", "choose_mcp_tool", "grounded_answer");

        ResponseEntity<Map<String, Object>> reloaded = http.exchange(
                "/api/investigations/" + investigation.get("id"), HttpMethod.GET, null, MAP);
        assertThat(reloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reloaded.getBody().get("resolvedAnswer")).isEqualTo(investigation.get("resolvedAnswer"));
        assertThat(list(reloaded.getBody().get("evidence"))).hasSize(1);
        assertThat(list(reloaded.getBody().get("steps"))).hasSize(1);
    }

    @AfterEach
    void disconnectTestMcpServer() {
        if (registeredServerId != null) {
            post("/api/tools/" + registeredServerId + "/disconnect", null);
            registeredServerId = null;
        }
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body) {
        return http.exchange(path, HttpMethod.POST, body == null ? null : new HttpEntity<>(body), MAP);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @TestConfiguration
    static class TestFixtures {

        @Bean
        ScriptedLlm scriptedLlm() {
            return new ScriptedLlm();
        }

        @Bean
        @Primary
        LlmClient scriptedLlmClient(ScriptedLlm scriptedLlm) {
            return scriptedLlm;
        }

        @Bean
        ToolCallCounter toolCallCounter() {
            return new ToolCallCounter();
        }

        @Bean
        HttpServletStreamableServerTransportProvider githubMcpTransport() {
            return HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .mcpEndpoint("/mcp")
                    .build();
        }

        @Bean
        ServletRegistrationBean<Servlet> githubMcpServlet(HttpServletStreamableServerTransportProvider transport) {
            ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(transport, "/mcp");
            registration.setName("scriptedGithubMcpServlet");
            return registration;
        }

        @Bean(destroyMethod = "close")
        McpSyncServer githubMcpServer(HttpServletStreamableServerTransportProvider transport, ToolCallCounter calls) {
            SyncToolSpecification searchRepositories = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("search_repositories", Map.of(
                                    "type", "object",
                                    "properties", Map.of("query", Map.of("type", "string")),
                                    "required", List.of("query")))
                            .description("Search GitHub repositories by query.")
                            .build())
                    .callHandler((exchange, request) -> {
                        calls.increment();
                        assertThat(request.arguments()).containsEntry("query", "user:okruti-sunita");
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(RAW_GITHUB_RESULT)))
                                .build();
                    })
                    .build();
            return McpServer.sync(transport)
                    .serverInfo("scripted-github", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(searchRepositories)
                    .build();
        }
    }

    static final class ScriptedLlm implements LlmClient {
        private final List<String> toolCalls = new ArrayList<>();
        private final AtomicInteger plannerCallCount = new AtomicInteger();

        @Override
        public JsonNode callTool(String system, String user, ToolSpec tool) {
            toolCalls.add(tool.name());
            try {
                if (tool.name().equals("grounded_answer")) {
                    return MAPPER.readTree(GROUNDED_ANSWER);
                }
                if (!tool.name().equals("choose_mcp_tool")) {
                    throw new AssertionError("Unexpected structured LLM tool: " + tool.name());
                }
                if (plannerCallCount.getAndIncrement() == 0) {
                    return MAPPER.readTree("""
                            {"action":"search_repositories","arguments":{"query":"user:okruti-sunita"},
                            "reason":"Search the requested account's repositories."}
                            """);
                }
                return MAPPER.readTree("""
                        {"action":"FINISH","arguments":{},"reason":"Repository search completed."}
                        """);
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid scripted LLM response", exception);
            }
        }

        @Override
        public String complete(String system, String user) {
            throw new AssertionError("Unexpected plain-text LLM request in investigation pipeline");
        }
    }

    static final class ToolCallCounter {
        private final AtomicInteger count = new AtomicInteger();

        void increment() { count.incrementAndGet(); }
        AtomicInteger calls() { return count; }
    }
}
