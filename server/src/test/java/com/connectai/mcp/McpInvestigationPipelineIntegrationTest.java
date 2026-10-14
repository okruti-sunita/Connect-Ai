package com.connectai.mcp;

import com.connectai.config.InvestigationExecutionConfig;
import com.connectai.mcp.client.McpRuntimeReconciler;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.service.InvestigationStore;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline against a REAL in-process MCP server built with the official SDK and
 * reached over Streamable HTTP: register -> handshake -> live discovery -> direct execution ->
 * investigation through the production path (catalog, planner, MCP execution, evidence,
 * persistence) -> disconnect / stale / failure scenarios.
 *
 * This validates Connect AI's MCP plumbing; it is NOT validation against GitHub. The LLM is
 * disabled here, so the deterministic rule-based planner chooses tools.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "connectai.tools.mode=mcp",
                "connectai.llm.enabled=false",
                "connectai.investigations.async=false",
                "connectai.seed-demo-data=false",
                "server.error.include-message=always"
        })
@Import(McpInvestigationPipelineIntegrationTest.TestMcpServer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpInvestigationPipelineIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    McpServerRepository servers;

    @Autowired
    McpRuntimeReconciler reconciler;

    @Autowired
    InvestigationStore store;

    @Autowired
    com.connectai.agent.AgentToolCatalog catalog;

    @Autowired
    InvestigationExecutionConfig investigationConfig;

    @Test
    @Order(1)
    void registerConnectDiscoverExecuteAndInvestigate() {
        String id = register("pipeline-main", "http://localhost:" + port + "/mcp", "NONE", null).get("id").toString();

        ResponseEntity<Map<String, Object>> connected = post("/api/tools/" + id + "/connect", null);
        assertThat(connected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(connected.getBody()).containsEntry("status", "CONNECTED").containsEntry("initialized", true);

        Map<String, Object> discovery = get("/api/tools/" + id + "/tools").getBody();
        assertThat(list(discovery.get("tools"))).extracting(t -> String.valueOf(t.get("name")))
                .contains("get_me", "broken_diagnostics");

        ResponseEntity<Map<String, Object>> direct = post("/api/tools/" + id + "/tools/get_me/execute", Map.of("arguments", Map.of()));
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(direct.getBody()).containsEntry("isError", false);

        Map<String, Object> listed = http.exchange("/api/tools", HttpMethod.GET, null, LIST).getBody().stream()
                .filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
        assertThat(listed).containsEntry("runtimeConnected", true).containsEntry("status", "CONNECTED");

        Map<String, Object> investigation = post("/api/investigations",
                Map.of("question", "Identify my authenticated account login")).getBody();
        assertThat(investigation.get("traceId")).isEqualTo(investigation.get("id"));

        // Recognised question: answered by the deterministic fast path (exact template, no model call).
        assertThat(investigation).containsEntry("status", "ANSWERED").containsEntry("route", "FAST_PATH");
        List<Map<String, Object>> steps = list(investigation.get("steps"));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("toolName", "get_me").containsEntry("status", "SUCCESS")
                .containsEntry("argumentsJson", "{}");
        List<Map<String, Object>> evidence = list(investigation.get("evidence"));
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0)).containsEntry("sourceRef", "E1").containsEntry("toolName", "get_me")
                .containsEntry("completeness", "UNKNOWN");
        assertThat(evidence.get(0).get("summary").toString()).contains("pipeline-test-user");
        assertThat(investigation.get("resolvedAnswer").toString()).contains("pipeline-test-user");

        // Reload: the persisted record matches what the POST returned.
        Map<String, Object> reloaded = get("/api/investigations/" + investigation.get("id")).getBody();
        assertThat(reloaded.get("status")).isEqualTo("ANSWERED");
        assertThat(list(reloaded.get("evidence"))).hasSize(1);
        assertThat(list(reloaded.get("evidence")).get(0)).containsEntry("completeness", "UNKNOWN");
        assertThat(reloaded.get("completedAt")).isNotNull();
    }

    @Test
    @Order(2)
    void writeToolsAreNeverOfferedToInvestigations() {
        assertThat(catalog.getAvailableTools()).extracting(c -> c.toolName())
                .contains("get_me")
                .doesNotContain("create_repository");
    }

    @Test
    @Order(3)
    void toolLevelErrorIsAFailedStepNotEvidence() {
        Map<String, Object> investigation = post("/api/investigations",
                Map.of("question", "Run the broken diagnostics")).getBody();

        assertThat(investigation).containsEntry("status", "FAILED");
        List<Map<String, Object>> steps = list(investigation.get("steps"));
        assertThat(steps.get(0)).containsEntry("toolName", "broken_diagnostics").containsEntry("status", "FAILED");
        assertThat(steps.get(0).get("errorMessage").toString()).contains("rate limited");
        assertThat(list(investigation.get("evidence"))).isEmpty();
    }

    @Test
    @Order(3)
    void questionWithNoRelevantToolIsInconclusive() {
        Map<String, Object> investigation = post("/api/investigations",
                Map.of("question", "What is the weather today?")).getBody();

        assertThat(investigation).containsEntry("status", "INCONCLUSIVE");
        assertThat(list(investigation.get("steps"))).isEmpty();
        assertThat(investigation.get("resolvedAnswer").toString()).contains("could not establish");
    }

    @Test
    @Order(4)
    void disconnectedServerReturns409AndInvestigationFailsHonestly() {
        disconnectAll();
        String id = servers.findAll().stream().filter(s -> s.getName().equals("pipeline-main"))
                .findFirst().orElseThrow().getId().toString();

        ResponseEntity<Map<String, Object>> direct = post("/api/tools/" + id + "/tools/get_me/execute", Map.of("arguments", Map.of()));
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(direct.getBody().get("message").toString()).contains("is not connected");

        Map<String, Object> investigation = post("/api/investigations",
                Map.of("question", "Identify my authenticated account login again")).getBody();
        assertThat(investigation).containsEntry("status", "FAILED");
        assertThat(investigation.get("stopReason").toString()).contains("No connected MCP tools");
    }

    @Test
    @Order(5)
    void persistedConnectedWithoutLiveSessionIsReportedDisconnected() {
        EMcpServer server = servers.findAll().stream().filter(s -> s.getName().equals("pipeline-main"))
                .findFirst().orElseThrow();
        server.markConnected(); // simulates the row left behind by a previous backend process
        servers.save(server);

        Map<String, Object> status = get("/api/tools/" + server.getId() + "/connection").getBody();
        assertThat(status).containsEntry("status", "DISCONNECTED").containsEntry("initialized", false);
        assertThat(status.get("error").toString()).contains("No live MCP session");

        server = servers.findById(server.getId()).orElseThrow();
        server.markConnected();
        servers.save(server);
        reconciler.reconcileOnStartup();
        assertThat(servers.findById(server.getId()).orElseThrow().getStatus().name()).isEqualTo("DISCONNECTED");
    }

    @Test
    @Order(6)
    void reconnectAfterDisconnectWorks() {
        String id = servers.findAll().stream().filter(s -> s.getName().equals("pipeline-main"))
                .findFirst().orElseThrow().getId().toString();
        assertThat(post("/api/tools/" + id + "/connect", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/tools/" + id + "/tools/get_me/execute", Map.of("arguments", Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(7)
    void unreachableServerFailsHandshakeWithActionableError() {
        String id = register("pipeline-unreachable", "http://127.0.0.1:1/mcp", "NONE", null).get("id").toString();

        ResponseEntity<Map<String, Object>> connect = post("/api/tools/" + id + "/connect", null);
        assertThat(connect.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(connect.getBody().get("message").toString()).contains("Could not connect");

        Map<String, Object> status = get("/api/tools/" + id + "/connection").getBody();
        assertThat(status).containsEntry("status", "FAILED");
        assertThat(status.get("error")).isNotNull();
    }

    @Test
    @Order(8)
    void invalidConfigurationIsRejectedBeforeSaving() {
        long before = servers.count();
        assertThat(postRaw("/api/tools", registration("bad-scheme", "ftp://example.com/mcp", "NONE", null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw("/api/tools", registration("no-token", "https://example.com/mcp", "BEARER", null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw("/api/tools", registration("PIPELINE-MAIN", "http://localhost:1/mcp", "NONE", null)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(servers.count()).isEqualTo(before);
    }

    @Test
    @Order(9)
    void secretIsNeverReturned() {
        Map<String, Object> created = register("pipeline-secret", "https://example.com/mcp", "BEARER", "super-secret-value");
        assertThat(created.toString()).doesNotContain("super-secret-value");
        assertThat(created).containsEntry("secretConfigured", true);
        assertThat(http.getForEntity("/api/tools", String.class).getBody()).doesNotContain("super-secret-value");
    }

    @Test
    @Order(10)
    void duplicateSubmissionOfARunningQuestionReturnsTheSameRun() {
        UUID running = store.createRunning("Still running question");
        Map<String, Object> again = post("/api/investigations", Map.of("question", "Still running question")).getBody();
        assertThat(again).containsEntry("id", running.toString()).containsEntry("status", "RUNNING");

        investigationConfig.failAbandonedRuns();
        Map<String, Object> after = get("/api/investigations/" + running).getBody();
        assertThat(after).containsEntry("status", "FAILED");
        assertThat(after.get("stopReason").toString()).contains("server stopped");
    }

    @Test
    @Order(11)
    void healthSeparatesBackendLlmAndMcp() {
        Map<String, Object> health = get("/api/health").getBody();
        assertThat(health).containsEntry("backend", "UP");
        assertThat(((Map<?, ?>) health.get("llm")).get("enabled")).isEqualTo(false);
        assertThat(list(health.get("mcpServers"))).anyMatch(s -> "pipeline-main".equals(s.get("name")) && Boolean.TRUE.equals(s.get("live")));
    }

    /** Two connections to the same kind of server (e.g. two GitHub accounts) are both usable and distinguishable. */
    @Test
    @Order(12)
    void multipleConnectionsOfTheSameToolAreSupported() {
        String second = register("pipeline-second", "http://localhost:" + port + "/mcp", "NONE", null).get("id").toString();
        assertThat(post("/api/tools/" + second + "/connect", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        var getMeTools = catalog.getAvailableTools().stream().filter(t -> t.toolName().equals("get_me")).toList();
        assertThat(getMeTools).hasSize(2);
        assertThat(getMeTools).extracting(t -> t.serverName()).containsExactlyInAnyOrder("pipeline-main", "pipeline-second");
        assertThat(getMeTools.get(0).plannerId()).isNotEqualTo(getMeTools.get(1).plannerId());

        Map<String, Object> investigation = post("/api/investigations",
                Map.of("question", "Identify my authenticated account login with both connections")).getBody();
        List<Map<String, Object>> steps = list(investigation.get("steps"));
        assertThat(steps).isNotEmpty();
        assertThat(steps).allSatisfy(step -> assertThat(step.get("serverName")).isIn("pipeline-main", "pipeline-second"));
    }

    /** Remove = disconnect first, then delete; history stays readable. */
    @Test
    @Order(13)
    void removeRequiresDisconnectFirstAndKeepsHistory() {
        String id = servers.findAll().stream().filter(s -> s.getName().equals("pipeline-second"))
                .findFirst().orElseThrow().getId().toString();
        Map<String, Object> history = post("/api/investigations",
                Map.of("question", "Identify my authenticated account login before removal")).getBody();

        ResponseEntity<Map<String, Object>> whileConnected = http.exchange("/api/tools/" + id, HttpMethod.DELETE, null, MAP);
        assertThat(whileConnected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(whileConnected.getBody().get("message").toString()).contains("Disconnect it before removing it");

        assertThat(post("/api/tools/" + id + "/disconnect", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/tools/" + id, HttpMethod.DELETE, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(servers.findById(UUID.fromString(id))).isEmpty();
        assertThat(http.exchange("/api/tools/" + id, HttpMethod.DELETE, null, MAP).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(catalog.getAvailableTools()).noneMatch(t -> t.serverId().toString().equals(id));

        // History of runs that used the removed connection is still served (name no longer resolvable).
        Map<String, Object> reloaded = get("/api/investigations/" + history.get("id")).getBody();
        assertThat(reloaded.get("status")).isNotEqualTo("RUNNING");
        assertThat(list(reloaded.get("steps"))).filteredOn(s -> id.equals(s.get("serverId")))
                .allSatisfy(s -> assertThat(s.get("serverName")).isNull());
    }

    @Test
    @Order(14)
    @SuppressWarnings("unchecked")
    void efficiencyIsMeasuredFromStoredRuns() {
        Map<String, Object> efficiency = get("/api/insights/efficiency").getBody();

        assertThat(((Number) efficiency.get("completedRuns")).longValue()).isPositive();
        Map<String, Object> routes = (Map<String, Object>) efficiency.get("routes");
        assertThat(routes).containsKey("FAST_PATH");
        Map<String, Object> fast = (Map<String, Object>) routes.get("FAST_PATH");
        assertThat(((Number) fast.get("runs")).longValue()).isPositive();
        assertThat(fast.get("medianSeconds")).isNotNull();
        assertThat(((Number) efficiency.get("fastPathShare")).doubleValue()).isBetween(0.0, 1.0);
        assertThat((Map<String, Object>) efficiency.get("outcomes")).containsKey("ANSWERED");
    }

    /** Close live sessions so the embedded server's graceful shutdown is not held open. */
    @AfterAll
    void closeSessions() {
        disconnectAll();
    }

    // ---------------------------------------------------------------- helpers

    private void disconnectAll() {
        servers.findAll().forEach(s -> post("/api/tools/" + s.getId() + "/disconnect", null));
    }

    private Map<String, Object> register(String name, String endpoint, String authType, String secret) {
        ResponseEntity<Map<String, Object>> response = postRaw("/api/tools", registration(name, endpoint, authType, secret));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static Map<String, Object> registration(String name, String endpoint, String authType, String secret) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("transport", "STREAMABLE_HTTP");
        body.put("endpoint", endpoint);
        body.put("authType", authType);
        body.put("secret", secret);
        body.put("enabled", true);
        return body;
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, null, MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body) {
        return postRaw(path, body);
    }

    private ResponseEntity<Map<String, Object>> postRaw(String path, Object body) {
        return http.exchange(path, HttpMethod.POST, body == null ? null : new HttpEntity<>(body), MAP);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** A real MCP server (official SDK, Streamable HTTP) served by the test's own web server. */
    @TestConfiguration
    static class TestMcpServer {

        @Bean
        HttpServletStreamableServerTransportProvider pipelineMcpTransport() {
            return HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .mcpEndpoint("/mcp")
                    .build();
        }

        @Bean
        ServletRegistrationBean<Servlet> pipelineMcpServlet(HttpServletStreamableServerTransportProvider transport) {
            ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(transport, "/mcp");
            registration.setName("pipelineMcpServlet");
            return registration;
        }

        @Bean(destroyMethod = "close")
        McpSyncServer pipelineMcpServer(HttpServletStreamableServerTransportProvider transport) {
            SyncToolSpecification getMe = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("get_me", Map.of("type", "object", "properties", Map.of()))
                            .description("Get details of the authenticated user, including the login.")
                            .build())
                    .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("{\"login\":\"pipeline-test-user\",\"id\":42}")))
                            .build())
                    .build();

            SyncToolSpecification broken = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("broken_diagnostics", Map.of("type", "object", "properties", Map.of()))
                            .description("Broken diagnostics tool that always reports an error.")
                            .build())
                    .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("rate limited, try later")))
                            .isError(true)
                            .build())
                    .build();

            SyncToolSpecification createRepo = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("create_repository", Map.of("type", "object", "properties", Map.of()))
                            .description("Create a repository for the authenticated user login.")
                            .build())
                    .callHandler((exchange, request) -> {
                        throw new IllegalStateException("write tool must never be called by an investigation");
                    })
                    .build();

            return McpServer.sync(transport)
                    .serverInfo("pipeline-test-server", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(createRepo, getMe, broken)
                    .build();
        }
    }
}
