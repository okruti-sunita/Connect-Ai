package com.connectai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Groq's OpenAI-compatible HTTP contract against a local stub. */
class GroqClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private GroqClient client;
    private volatile int status = 200;
    private volatile String responseJson = "{}";
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new GroqClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "test-model", 256, Duration.ofSeconds(5), 0, mapper);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void callTool_returnsSchemaConstrainedJsonAndDoesNotSendFunctionTools() throws Exception {
        responseJson = """
                {"choices":[{"message":{"content":"{\\"action\\":\\"T1\\",\\"arguments\\":{},\\"reason\\":\\"matched\\"}"}}]}
                """;
        ToolSpec spec = tool();

        JsonNode result = client.callTool("system text", "user text", spec);

        assertThat(result.path("action").asText()).isEqualTo("T1");
        assertThat(result.path("reason").asText()).isEqualTo("matched");
        JsonNode sent = mapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(256);
        assertThat(sent.path("messages").get(0).path("content").asText())
                .contains("system text", "Return a JSON object matching the supplied response schema", "Choose one");
        assertThat(sent.path("messages").get(1).path("content").asText()).isEqualTo("user text");
        assertThat(sent.path("tools").isMissingNode()).isTrue();
        assertThat(sent.path("tool_choice").isMissingNode()).isTrue();
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(sent.path("response_format").path("json_schema").path("name").asText())
                .isEqualTo("choose_mcp_tool");
        assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isFalse();
        assertThat(sent.path("response_format").path("json_schema").path("schema")
                .path("properties").path("action").path("enum").toString()).contains("T1", "FINISH");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void missingOrEmptyToolCallsWithoutContentAreErrors() {
        responseJson = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class).hasMessageContaining("choose_mcp_tool");

        responseJson = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":[]}}]}";
        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class).hasMessageContaining("choose_mcp_tool");
    }

    @Test
    void malformedAndIncompleteResponseJsonBecomeLlmExceptions() {
        responseJson = "not json";
        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Groq response was not valid JSON")
                .hasMessageNotContaining("Could not communicate");

        responseJson = "{\"choices\":[";
        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Groq response was not valid JSON")
                .hasMessageNotContaining("Could not communicate");
    }

    @Test
    void missingMessageContentAndToolCallsAreReportedAsMissingToolArguments() {
        responseJson = "{\"choices\":[{\"message\":{}}]}";

        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Groq did not return JSON arguments for 'choose_mcp_tool'");
    }

    @Test
    void fallsBackToTextualJsonContentWhenToolCallIsAbsent() throws Exception {
        responseJson = """
                {"choices":[{"message":{"content":"{\\"action\\":\\"FINISH\\",\\"arguments\\":{},\\"reason\\":\\"done\\"}"}}]}
                """;

        JsonNode result = client.callTool("s", "u", tool());

        assertThat(result.path("action").asText()).isEqualTo("FINISH");
        assertThat(result.path("reason").asText()).isEqualTo("done");
    }

    @Test
    void providerApiErrorIncludesStatusAndBodyWithoutExposingApiKey() {
        status = 401;
        responseJson = "{\"error\":{\"message\":\"invalid API key\"}}";

        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Groq HTTP 401")
                .hasMessageContaining("invalid API key")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void groqJsonWrapperValidationFailureIsReportedWithoutLeakingCredentials() throws Exception {
        status = 400;
        responseJson = """
                {"error":{"message":"Tool call validation failed: attempted to call tool 'json' which was not in request.tools",
                 "failed_generation":"{\\\"name\\\":\\\"json\\\",\\\"arguments\\\":{\\\"action\\\":\\\"FINISH\\\"}}"}}
                """;

        assertThatThrownBy(() -> client.callTool("s", "u", tool()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Groq HTTP 400")
                .hasMessageContaining("attempted to call tool 'json'")
                .hasMessageNotContaining("test-key");
        JsonNode sent = mapper.readTree(requestBody.get());
        assertThat(sent.path("tools").isMissingNode()).isTrue();
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    private static ToolSpec tool() {
        return new ToolSpec("choose_mcp_tool", "Choose one", Map.of(
                "type", "object",
                "properties", Map.of("action", Map.of("type", "string", "enum", List.of("T1", "FINISH"))),
                "required", List.of("action")));
    }
}
