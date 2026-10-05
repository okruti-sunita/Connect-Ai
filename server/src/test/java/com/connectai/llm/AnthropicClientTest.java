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

/** Exercises the real HTTP client against a tiny local server that imitates the Anthropic API. */
class AnthropicClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AnthropicClient client;

    private volatile int status = 200;
    private volatile String responseJson = "{}";
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> versionHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        client = new AnthropicClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "test-model", 256, Duration.ofSeconds(5), mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callTool_forcesTheToolAndReturnsItsInput() throws Exception {
        responseJson = """
                {"content":[{"type":"tool_use","id":"t1","name":"choose_next_step",
                  "input":{"action":"JIRA","reason":"ticket found"}}],"stop_reason":"tool_use"}""";
        ToolSpec tool = new ToolSpec("choose_next_step", "pick one",
                Map.of("type", "object", "properties", Map.of("action", Map.of("type", "string")),
                        "required", List.of("action")));

        JsonNode input = client.callTool("system text", "user text", tool);

        assertThat(input.path("action").asText()).isEqualTo("JIRA");

        JsonNode sent = mapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(sent.path("system").asText()).isEqualTo("system text");
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo("user text");
        assertThat(sent.path("tools").get(0).path("name").asText()).isEqualTo("choose_next_step");
        assertThat(sent.path("tool_choice").path("type").asText()).isEqualTo("tool");
        assertThat(sent.path("tool_choice").path("name").asText()).isEqualTo("choose_next_step");
        assertThat(apiKeyHeader.get()).isEqualTo("test-key");
        assertThat(versionHeader.get()).isEqualTo("2023-06-01");
    }

    @Test
    void complete_joinsTheTextBlocks() {
        responseJson = """
                {"content":[{"type":"text","text":"Hello "},{"type":"text","text":"world [1]"}]}""";

        assertThat(client.complete("s", "u")).isEqualTo("Hello world [1]");
    }

    @Test
    void anHttpErrorBecomesAnLlmExceptionWithoutLeakingTheKey() {
        status = 401;
        responseJson = "{\"error\":{\"type\":\"authentication_error\"}}";

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void aModelThatIgnoresTheToolIsAnError() {
        responseJson = "{\"content\":[{\"type\":\"text\",\"text\":\"I would rather chat\"}]}";
        ToolSpec tool = new ToolSpec("choose_next_step", "pick one", Map.of("type", "object"));

        assertThatThrownBy(() -> client.callTool("s", "u", tool))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("choose_next_step");
    }
}
