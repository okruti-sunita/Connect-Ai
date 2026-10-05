package com.connectai.mcp.client;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.security.SecretEncryptionService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class McpClientFactory {

    private final SecretEncryptionService encryptionService;
    private final Duration requestTimeout;
    private final Duration connectTimeout;

    public McpClientFactory(
            SecretEncryptionService encryptionService,
            @Value("${connectai.mcp.request-timeout-seconds:20}") long requestTimeoutSeconds,
            @Value("${connectai.mcp.connect-timeout-seconds:10}") long connectTimeoutSeconds) {
        this.encryptionService = encryptionService;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
    }

    public McpSyncClient create(EMcpServer server) {
        if (server.getTransport() == null) {
            throw new IllegalArgumentException("MCP transport is required");
        }
        if (server.getTransport() != McpTransportType.STREAMABLE_HTTP) {
            throw new UnsupportedOperationException(
                    "Task 2 supports STREAMABLE_HTTP only; received " + server.getTransport());
        }

        McpEndpoint endpoint = McpEndpoint.parse(server.getEndpoint());
        HttpClientStreamableHttpTransport.Builder transportBuilder =
                HttpClientStreamableHttpTransport
                        .builder(endpoint.baseUrl())
                        .endpoint(endpoint.path())
                        .connectTimeout(connectTimeout);

        if (server.getAuthType() == McpAuthType.BEARER) {
            String secret = encryptionService.decrypt(server.getEncryptedSecret());
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "Bearer authentication is configured but no MCP secret is available");
            }
            transportBuilder.httpRequestCustomizer(
                    (requestBuilder, method, uri, body, context) ->
                            requestBuilder.header("Authorization", "Bearer " + secret));
        }

        return McpClient.sync(transportBuilder.build())
                .clientInfo(new McpSchema.Implementation("Connect AI", "0.1.0"))
                .requestTimeout(requestTimeout)
                .build();
    }
}
