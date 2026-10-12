package com.connectai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Parsing fixtures are shaped like real Ollama /api/chat responses. The HTTP tests use a
 * local stub HTTP server (not a real model) to check the request contract and error mapping.
 */
class OllamaClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- parsing fixtures

    @Test
    void parsesPlainJsonContent() {
        JsonNode node = OllamaClient.parseJsonObject("{\"action\":\"T1\",\"arguments\":{},\"reason\":\"r\"}", MAPPER);
        assertThat(node.path("action").asText()).isEqualTo("T1");
    }

    @Test
    void stripsQwenThinkBlockAndMarkdownFence() {
        String raw = "<think>\nThe user wants their login, so get_me.\n</think>\n```json\n{\"action\": \"T1\", \"arguments\": {}}\n```";
        assertThat(OllamaClient.parseJsonObject(raw, MAPPER).path("action").asText()).isEqualTo("T1");
    }

    @Test
    void extractsObjectSurroundedByProse() {
        String raw = "Sure! Here is my decision: {\"action\": \"FINISH\", \"arguments\": {}, \"reason\": \"has {braces} in text\"} Hope that helps.";
        assertThat(OllamaClient.parseJsonObject(raw, MAPPER).path("reason").asText()).isEqualTo("has {braces} in text");
    }

    @Test
    void rejectsTruncatedJson() {
        assertThatThrownBy(() -> OllamaClient.parseJsonObject("{\"action\": \"T1\", \"argum", MAPPER))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsEmptyAndThinkOnlyContent() {
        assertThatThrownBy(() -> OllamaClient.parseJsonObject("", MAPPER)).hasMessageContaining("empty");
        assertThatThrownBy(() -> OllamaClient.parseJsonObject("<think>still thinking about it", MAPPER))
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertThatThrownBy(() -> OllamaClient.parseJsonObject("[\"T1\"]", MAPPER))
                .hasMessageContaining("not an object");
    }

    @Test
    void prefersStructuredToolCallArguments() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"message":{"role":"assistant","content":"",
                 "tool_calls":[{"function":{"name":"choose_mcp_tool","arguments":{"action":"T2","arguments":{"q":"x"}}}}]},
                 "done":true}""");
        assertThat(OllamaClient.interpretStructuredResponse(response, MAPPER).path("action").asText()).isEqualTo("T2");
    }

    @Test
    void reportsTokenLimitTruncation() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"message":{"role":"assistant","content":"{\\"action\\": \\"T1\\", \\"reason\\": \\"bec"},
                 "done":true,"done_reason":"length"}""");
        assertThatThrownBy(() -> OllamaClient.interpretStructuredResponse(response, MAPPER))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void modelInstalledCheckToleratesLatestTag() {
        assertThat(OllamaClient.isInstalled("llama3", List.of("llama3:latest"))).isTrue();
        assertThat(OllamaClient.isInstalled("qwen3:1.7b", List.of("qwen3:8b"))).isFalse();
    }

    // ---------------------------------------------------------------- HTTP contract (stub server)

    @Test
    void sendsSchemaConstrainedRequestWithThinkingDisabledAndExplicitContext() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"action\\\":\\\"FINISH\\\",\\\"arguments\\\":{},\\\"reason\\\":\\\"done\\\"}\"},\"done\":true}", body);

        OllamaClient client = client();
        ToolSpec spec = new ToolSpec("choose", "d", Map.of("type", "object",
                "properties", Map.of("action", Map.of("type", "string", "enum", List.of("T1", "FINISH")))));
        JsonNode decision = client.callTool("system", "user", spec);

        assertThat(decision.path("action").asText()).isEqualTo("FINISH");
        JsonNode sent = MAPPER.readTree(body.get());
        assertThat(sent.path("think").asBoolean(true)).isFalse();
        assertThat(sent.path("stream").asBoolean(true)).isFalse();
        assertThat(sent.path("format").path("properties").path("action").path("enum").toString()).contains("FINISH");
        assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(4096);
        assertThat(sent.has("tools")).as("no OpenAI-style tools list is sent").isFalse();
    }

    @Test
    void missingModelGivesActionableError() throws Exception {
        start(404, "{\"error\":\"model 'qwen3:1.7b' not found\"}", new AtomicReference<>());

        assertThatThrownBy(() -> client().complete("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("ollama pull qwen3:1.7b");
    }

    @Test
    void completionStripsThinkBlock() throws Exception {
        start(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"<think>hmm</think>Your login is octo [E1].\"},\"done\":true}",
                new AtomicReference<>());

        assertThat(client().complete("s", "u")).isEqualTo("Your login is octo [E1].");
    }

    @Test
    void unreachableOllamaGivesActionableError() {
        OllamaClient client = new OllamaClient("http://127.0.0.1:1", "qwen3:1.7b", Duration.ofSeconds(2), MAPPER);
        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Could not reach Ollama");
    }

    private OllamaClient client() {
        return new OllamaClient("http://127.0.0.1:" + server.getAddress().getPort(), "qwen3:1.7b",
                Duration.ofSeconds(5), MAPPER, 4096, 256, 0.0);
    }

    private void start(int status, String response, AtomicReference<String> capturedBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
