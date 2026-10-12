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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic fast path against a REAL MCP server (official SDK, Streamable HTTP) whose tools look
 * nothing like GitHub's: whoami, list_tickets(state, limit), get_ticket(key). Proves that recognised
 * questions are answered from any server's tools with no model call.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "connectai.tools.mode=mcp",
                "connectai.llm.enabled=false",
                "connectai.investigations.async=false",
                "connectai.seed-demo-data=false"
        })
@Import(GenericMcpServerIntegrationTest.TicketServer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericMcpServerIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    static final AtomicReference<Map<String, Object>> LAST_LIST_ARGS = new AtomicReference<>();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    McpServerRepository servers;

    @Autowired
    McpConnectionService connections;

    @Autowired
    com.connectai.agent.AgentToolCatalog catalog;

    @Autowired
    com.connectai.agent.PlannedToolExecutor executor;

    @Autowired
    com.connectai.service.LearnedRecipeService learned;

    @BeforeAll
    void connect() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Ops Tickets");
        body.put("transport", "STREAMABLE_HTTP");
        body.put("endpoint", "http://localhost:" + port + "/tickets-mcp");
        body.put("authType", "NONE");
        body.put("enabled", true);
        String id = http.exchange("/api/tools", HttpMethod.POST, new HttpEntity<>(body), MAP).getBody().get("id").toString();
        assertThat(http.exchange("/api/tools/" + id + "/connect", HttpMethod.POST, null, MAP).getBody())
                .containsEntry("status", "CONNECTED");
    }

    private Map<String, Object> investigate(String question) {
        return http.exchange("/api/investigations", HttpMethod.POST, new HttpEntity<>(Map.of("question", question)), MAP).getBody();
    }

    @Test
    void identityFromAnyIdentityTool() {
        Map<String, Object> result = investigate("who am i");

        assertThat(result).containsEntry("status", "ANSWERED").containsEntry("route", "FAST_PATH");
        assertThat(result.get("resolvedAnswer").toString()).contains("Signed in as **ops-bot**");
    }

    @Test
    void listWithAFilterTheToolDeclares() {
        Map<String, Object> result = investigate("list the last 2 open tickets");

        assertThat(result).containsEntry("status", "ANSWERED").containsEntry("route", "FAST_PATH");
        assertThat(result.get("routeDetail").toString()).contains("tool list_tickets")
                .contains("state ← state in the question").contains("limit ← count in the question");
        assertThat(LAST_LIST_ARGS.get()).containsEntry("state", "open").containsEntry("limit", 2);
        assertThat(result.get("resolvedAnswer").toString())
                .contains("`OPS-7` Disk almost full on db-1").contains("open").contains("sre-oncall");
    }

    @Test
    void oneItemByKey() {
        Map<String, Object> result = investigate("show OPS-7");

        assertThat(result).containsEntry("route", "FAST_PATH");
        assertThat(result.get("resolvedAnswer").toString()).contains("`OPS-7` Disk almost full on db-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step).containsEntry("toolName", "get_ticket");
            assertThat(step.get("argumentsJson").toString()).contains("\"key\":\"OPS-7\"");
        });
    }

    @Test
    void questionsNeedingJudgementAreNotFastPathed() {
        Map<String, Object> result = investigate("why do tickets keep piling up");
        assertThat(result.get("route")).isNotEqualTo("FAST_PATH");
        assertThat(result.get("routeDetail").toString()).contains("needs judgement").contains("why");
    }

    /**
     * Self-learning: a plan learned from an answered run is replayed for a question of the same shape
     * with the new question's values (real tool calls, no planner). With the LLM off, the answer step is
     * the evidence-listing template, which is PARTIAL, so the replay also disables the recipe: a recipe
     * only stays active while its replays end ANSWERED.
     */
    @Test
    void learnedPlanIsReplayedWithTheNewQuestionsValues() {
        var tools = catalog.getAvailableTools();
        var ticket = tools.stream().filter(t -> t.toolName().equals("get_ticket")).findFirst().orElseThrow();
        String learnedFrom = "summarize OPS-7 for the standup";
        var step = executor.execute(1, new com.connectai.agent.ToolSelectionPlan(
                ticket.serverId(), "get_ticket", Map.of("key", "OPS-7"), "test: the AI agent's plan"));
        var execution = com.connectai.agent.AgentExecutionState.initial(learnedFrom).addStep(step)
                .finish(com.connectai.agent.AgentExecutionStatus.COMPLETED, "done");
        learned.learn(java.util.UUID.randomUUID(), learnedFrom, execution, com.connectai.domain.InvestigationStatus.ANSWERED, tools);
        assertThat(learned.all()).anySatisfy(r -> assertThat(r.getQuestionTemplate()).isEqualTo("summarize {key} for the standup"));

        Map<String, Object> result = investigate("Summarize OPS-9 for the standup");

        assertThat(result).containsEntry("route", "LEARNED");
        assertThat(result.get("routeDetail").toString()).contains("learned plan was replayed").contains("key = OPS-9");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s).containsEntry("toolName", "get_ticket");
            assertThat(s.get("argumentsJson").toString()).contains("\"key\":\"OPS-9\"");
        });
        assertThat(learned.all()).filteredOn(r -> r.getQuestionTemplate().equals("summarize {key} for the standup"))
                .singleElement().satisfies(r -> {
                    assertThat(r.getUseCount()).isEqualTo(1);
                    assertThat(r.isDisabled()).as("a PARTIAL replay disables the recipe").isTrue();
                });

        assertThat(investigate("Summarize OPS-3 for the standup").get("route"))
                .as("a disabled recipe is not replayed").isEqualTo("AI_AGENT");
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

    /** A real MCP server with ticket-system tools, served by the test's own web server. */
    @TestConfiguration
    static class TicketServer {

        @Bean
        HttpServletStreamableServerTransportProvider ticketsTransport() {
            return HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/tickets-mcp").build();
        }

        @Bean
        ServletRegistrationBean<Servlet> ticketsServlet(HttpServletStreamableServerTransportProvider transport) {
            ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(transport, "/tickets-mcp");
            registration.setName("ticketsMcpServlet");
            return registration;
        }

        private static McpSchema.CallToolResult text(String json) {
            return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(json))).build();
        }

        @Bean(destroyMethod = "close")
        McpSyncServer ticketsMcpServer(HttpServletStreamableServerTransportProvider transport) {
            var whoami = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("whoami", Map.of("type", "object", "properties", Map.of()))
                            .description("Returns the account the server is connected as.").build())
                    .callHandler((exchange, request) -> text("{\"username\":\"ops-bot\",\"team\":\"SRE\"}"))
                    .build();
            var listTickets = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("list_tickets", Map.of("type", "object", "properties", Map.of(
                                    "state", Map.of("type", "string", "enum", List.of("open", "closed")),
                                    "limit", Map.of("type", "integer"))))
                            .description("List tickets in the ops queue.").build())
                    .callHandler((exchange, request) -> {
                        LAST_LIST_ARGS.set(request.arguments());
                        return text("[{\"key\":\"OPS-7\",\"title\":\"Disk almost full on db-1\",\"status\":\"open\",\"assignee\":\"sre-oncall\"},"
                                + "{\"key\":\"OPS-9\",\"title\":\"Rotate TLS certificate\",\"status\":\"open\"}]");
                    })
                    .build();
            var getTicket = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("get_ticket", Map.of("type", "object",
                                    "properties", Map.of("key", Map.of("type", "string")), "required", List.of("key")))
                            .description("Get one ticket by key.").build())
                    .callHandler((exchange, request) -> text("{\"key\":\"" + request.arguments().get("key")
                            + "\",\"title\":\"Disk almost full on db-1\",\"status\":\"open\"}"))
                    .build();
            return McpServer.sync(transport)
                    .serverInfo("ticket-test-server", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(whoami, listTickets, getTicket)
                    .build();
        }
    }
}
