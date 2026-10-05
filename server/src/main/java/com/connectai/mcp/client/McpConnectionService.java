package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpConnectionResponse;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpConnectionService {

    private final McpServerRepository repository;
    private final McpClientFactory clientFactory;
    private final McpClientRegistry clientRegistry;

    public McpConnectionResponse connect(UUID serverId) {
        EMcpServer server = findServer(serverId);

        if (!server.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "MCP server " + serverId + " is disabled");
        }

        server.markConnecting();
        repository.save(server);

        McpSyncClient client = null;

        try {
            log.info(
                    "Starting MCP handshake: serverId={}, name={}",
                    serverId,
                    server.getName());

            client = clientFactory.create(server);

            McpSchema.InitializeResult initializeResult = client.initialize();

            clientRegistry.register(serverId, client);

            server.markConnected();
            repository.save(server);

            log.info(
                    "MCP handshake successful: serverId={}, protocolVersion={}, server={}/{}",
                    serverId,
                    initializeResult.protocolVersion(),
                    initializeResult.serverInfo().name(),
                    initializeResult.serverInfo().version());

            return new McpConnectionResponse(
                    serverId,
                    McpConnectionStatus.CONNECTED,
                    true,
                    initializeResult.protocolVersion(),
                    initializeResult.serverInfo().name(),
                    initializeResult.serverInfo().version(),
                    server.getLastConnectedAt(),
                    null);

        } catch (Exception exception) {
            closeClientAfterFailure(client);

            server.markFailed(safeErrorMessage(exception));
            repository.save(server);

            log.error(
                    "MCP handshake failed: serverId={}, name={}",
                    serverId,
                    server.getName(),
                    exception);

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "MCP handshake failed for server "
                            + serverId
                            + ": "
                            + safeErrorMessage(exception),
                    exception);
        }
    }

    public McpConnectionResponse disconnect(UUID serverId) {
        EMcpServer server = findServer(serverId);

        clientRegistry.remove(serverId).ifPresent(client -> {
            try {
                client.closeGracefully();
            } catch (Exception exception) {
                log.warn(
                        "Error while closing MCP client: serverId={}",
                        serverId,
                        exception);
            }
        });

        server.markDisconnected();
        repository.save(server);

        return new McpConnectionResponse(
                serverId,
                McpConnectionStatus.DISCONNECTED,
                false,
                null,
                null,
                null,
                server.getLastConnectedAt(),
                null);
    }

    public McpConnectionResponse status(UUID serverId) {
        EMcpServer server = findServer(serverId);

        boolean connected = clientRegistry.isConnected(serverId);

        return new McpConnectionResponse(
                serverId,
                connected
                        ? McpConnectionStatus.CONNECTED
                        : server.getStatus(),
                connected,
                null,
                null,
                null,
                server.getLastConnectedAt(),
                server.getLastError());
    }

    private EMcpServer findServer(UUID serverId) {
        return repository.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP server " + serverId + " not found"));
    }

    private void closeClientAfterFailure(McpSyncClient client) {
        if (client == null) {
            return;
        }

        try {
            client.closeGracefully();
        } catch (Exception closeException) {
            log.warn(
                    "Failed to close MCP client after handshake failure",
                    closeException);
        }
    }

    private String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return message.length() > 1000
                ? message.substring(0, 1000)
                : message;
    }
}