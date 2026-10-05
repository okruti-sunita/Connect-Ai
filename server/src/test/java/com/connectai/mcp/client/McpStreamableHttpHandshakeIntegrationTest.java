package com.connectai.mcp.client;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStreamableHttpHandshakeIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private ExecutorService executor;
    private McpSyncClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("localhost", 0),
                0);

        server.createContext("/mcp", this::handleMcpRequest);

        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        server.start();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                // Cleanup must not hide the actual test failure.
            }
        }

        if (server != null) {
            server.stop(0);
        }

        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void realSdkPerformsStreamableHttpInitializeHandshake() {

        EMcpServer serverConfig = new EMcpServer(
                "local-test-mcp",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:"
                        + server.getAddress().getPort()
                        + "/mcp",
                McpAuthType.NONE,
                null,
                true);

        McpClientFactory factory = new McpClientFactory(
                new SecretEncryptionService("integration-test-key"),
                10,
                5);

        client = factory.create(serverConfig);

        McpSchema.InitializeResult result = client.initialize();

        assertNotNull(result);

        assertEquals(
                "2025-06-18",
                result.protocolVersion());

        assertEquals(
                "connect-ai-test-server",
                result.serverInfo().name());

        assertEquals(
                "1.0.0",
                result.serverInfo().version());

        assertTrue(client.isInitialized());
    }

    private void handleMcpRequest(
            HttpExchange exchange) throws IOException {

        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);

        try {
            JsonNode request = objectMapper.readTree(requestBody);

            JsonNode methodNode = request.get("method");

            if (methodNode == null || !methodNode.isTextual()) {
                sendJson(
                        exchange,
                        400,
                        """
                        {
                          "jsonrpc": "2.0",
                          "error": {
                            "code": -32600,
                            "message": "Missing JSON-RPC method"
                          }
                        }
                        """
                );
                return;
            }

            String method = methodNode.asText();

            /*
             * IMPORTANT:
             *
             * Do not use contains("initialize") here.
             *
             * "notifications/initialized" also contains the text
             * "initialize".
             *
             * We must compare the actual JSON-RPC method exactly.
             */
            if ("initialize".equals(method)) {
                handleInitialize(exchange, request);
                return;
            }

            if ("notifications/initialized".equals(method)) {
                handleInitializedNotification(exchange);
                return;
            }

            sendJson(
                    exchange,
                    400,
                    """
                    {
                      "jsonrpc": "2.0",
                      "error": {
                        "code": -32601,
                        "message": "Method not supported"
                      }
                    }
                    """
            );

        } finally {
            exchange.close();
        }
    }

    private void handleInitialize(
            HttpExchange exchange,
            JsonNode request) throws IOException {

        JsonNode idNode = request.get("id");

        /*
         * initialize is a JSON-RPC request, so it MUST have an id.
         */
        if (idNode == null || idNode.isNull()) {
            sendJson(
                    exchange,
                    400,
                    """
                    {
                      "jsonrpc": "2.0",
                      "error": {
                        "code": -32600,
                        "message": "Missing JSON-RPC id"
                      }
                    }
                    """
            );
            return;
        }

        String response = """
                {
                  "jsonrpc": "2.0",
                  "id": %s,
                  "result": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "serverInfo": {
                      "name": "connect-ai-test-server",
                      "version": "1.0.0"
                    }
                  }
                }
                """.formatted(idNode.toString());

        sendJson(
                exchange,
                200,
                response);
    }

    private void handleInitializedNotification(
            HttpExchange exchange) throws IOException {

        /*
         * notifications/initialized is a JSON-RPC notification.
         *
         * Notifications intentionally have NO id and receive
         * no JSON-RPC response body.
         *
         * Streamable HTTP uses 202 Accepted for this case.
         */
        exchange.sendResponseHeaders(202, -1);
    }

    private void sendJson(
            HttpExchange exchange,
            int status,
            String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json");

        exchange.sendResponseHeaders(
                status,
                bytes.length);

        try (OutputStream outputStream =
                     exchange.getResponseBody()) {

            outputStream.write(bytes);
        }
    }
}