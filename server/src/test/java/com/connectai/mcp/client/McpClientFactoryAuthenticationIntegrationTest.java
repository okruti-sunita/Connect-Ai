package com.connectai.mcp.client;

import com.connectai.mcp.catalog.ConnectMethod;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpClientFactoryAuthenticationIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretEncryptionService encryption = new SecretEncryptionService("integration-test-key-long-enough-for-mcp-auth");
    private final AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>(Map.of());
    private HttpServer server;
    private ExecutorService executor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", this::handle);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void appliesBearerBasicCustomMultipleAndNoAuthenticationHeaders() {
        assertHeaders(ConnectMethod.TOKEN_BEARER, Map.of("token", "bearer-test"),
                Map.of("authorization", "Bearer bearer-test"));
        assertHeaders(ConnectMethod.TOKEN_BASIC, Map.of("username", "person@example.test", "token", "basic-test"),
                Map.of("authorization", "Basic cGVyc29uQGV4YW1wbGUudGVzdDpiYXNpYy10ZXN0"));
        assertHeaders(ConnectMethod.TOKEN_HEADER, Map.of("headerName", "X-Access-Key", "token", "header-test"),
                Map.of("x-access-key", "header-test"));
        assertHeaders(ConnectMethod.TOKEN_HEADERS,
                Map.of("header:X-Api-Key", "api-test", "header:X-App-Key", "app-test"),
                Map.of("x-api-key", "api-test", "x-app-key", "app-test"));
        assertHeaders(ConnectMethod.NONE, Map.of(), Map.of());
    }

    private void assertHeaders(ConnectMethod method, Map<String, String> credentials,
                               Map<String, String> expected) {
        String json;
        try { json = mapper.writeValueAsString(credentials); }
        catch (IOException exception) { throw new AssertionError(exception); }
        EMcpServer configuration = new EMcpServer("fake", McpTransportType.STREAMABLE_HTTP,
                "http://localhost:" + server.getAddress().getPort() + "/mcp", McpAuthType.BEARER, null, true);
        configuration.setConnectionAuth(method, method == ConnectMethod.NONE ? null : encryption.encrypt(json));
        McpSyncClient client = new McpClientFactory(encryption, 10, 5).create(configuration);
        try {
            client.initialize();
            Map<String, String> normalized = new LinkedHashMap<>();
            expected.forEach((name, value) -> normalized.put(name, receivedHeaders.get().get(name)));
            assertEquals(expected, normalized);
        } finally {
            client.closeGracefully();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> current = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) current.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst());
        });
        receivedHeaders.set(Map.copyOf(current));
        JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        if ("initialize".equals(request.path("method").asText())) {
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id")
                    + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"fake-auth-server\",\"version\":\"1\"}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } else {
            exchange.sendResponseHeaders(202, -1);
        }
        exchange.close();
    }
}
