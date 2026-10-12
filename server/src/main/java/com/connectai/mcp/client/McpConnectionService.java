package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpConnectionResponse;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Owns the lifecycle of live MCP sessions: connect (real handshake), disconnect and status.
 *
 * Persisted status is only ever CONNECTED after a completed initialize handshake, and a
 * persisted CONNECTED without a live runtime session (for example after a backend restart)
 * is reported and stored as DISCONNECTED rather than being trusted.
 *
 * Connect and disconnect for the same server are serialised; different servers proceed
 * independently, so one slow or failing server never blocks the others.
 */
@Service
@Slf4j
public class McpConnectionService {

    static final String STALE_SESSION_MESSAGE =
            "No live MCP session (the backend restarted or the session was closed). Connect again.";

    private final McpServerRepository repository;
    private final McpClientFactory clientFactory;
    private final McpClientRegistry clientRegistry;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public McpConnectionService(
            McpServerRepository repository,
            McpClientFactory clientFactory,
            McpClientRegistry clientRegistry) {
        this.repository = repository;
        this.clientFactory = clientFactory;
        this.clientRegistry = clientRegistry;
    }

    public McpConnectionResponse connect(UUID serverId) {
        synchronized (lockFor(serverId)) {
            return doConnect(serverId);
        }
    }

    private McpConnectionResponse doConnect(UUID serverId) {
        EMcpServer server = findServer(serverId);

        if (!server.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "MCP server '" + server.getName() + "' is disabled; enable it before connecting");
        }

        if (server.getAuthType() == com.connectai.mcp.model.McpAuthType.OAUTH && !server.hasCredential()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "'" + server.getName() + "' is not signed in yet. Use 'Sign in' first.");
        }

        server.markConnecting();
        repository.save(server);

        McpSyncClient client = null;
        long started = System.nanoTime();

        try {
            log.info(
                    "Starting MCP handshake: serverId={}, name={}, transport={}, endpointHost={}",
                    serverId,
                    server.getName(),
                    server.getTransport(),
                    safeEndpointHost(server.getEndpoint()));

            client = clientFactory.create(server);
            McpSchema.InitializeResult initializeResult = client.initialize();

            if (initializeResult == null) {
                throw new IllegalStateException("MCP server returned no initialize result");
            }

            // Replaces (and closes) any previous session for this server.
            clientRegistry.register(serverId, client);

            server.markConnected();
            repository.save(server);

            String serverName = initializeResult.serverInfo() == null ? null : initializeResult.serverInfo().name();
            String serverVersion = initializeResult.serverInfo() == null ? null : initializeResult.serverInfo().version();

            log.info(
                    "MCP handshake successful: serverId={}, protocolVersion={}, server={}/{}, elapsedMs={}",
                    serverId,
                    initializeResult.protocolVersion(),
                    serverName,
                    serverVersion,
                    elapsedMs(started));

            return new McpConnectionResponse(
                    serverId,
                    McpConnectionStatus.CONNECTED,
                    true,
                    initializeResult.protocolVersion(),
                    serverName,
                    serverVersion,
                    server.getLastConnectedAt(),
                    null);

        } catch (Exception exception) {
            closeQuietly(client);
            // A failed reconnect must not leave an older session looking usable.
            clientRegistry.remove(serverId).ifPresent(McpConnectionService::closeQuietly);

            String error = describeFailure(exception);
            server.markFailed(error);
            repository.save(server);

            log.warn(
                    "MCP handshake failed: serverId={}, name={}, elapsedMs={}, cause={}",
                    serverId,
                    server.getName(),
                    elapsedMs(started),
                    error);
            log.debug("MCP handshake failure detail: serverId={}", serverId, exception);

            HttpStatus status = isConfigurationError(exception) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Could not connect to '" + server.getName() + "': " + error, exception);
        }
    }

    public McpConnectionResponse disconnect(UUID serverId) {
        synchronized (lockFor(serverId)) {
            EMcpServer server = findServer(serverId);

            clientRegistry.remove(serverId).ifPresent(McpConnectionService::closeQuietly);

            server.markDisconnected();
            repository.save(server);
            log.info("MCP server disconnected: serverId={}, name={}", serverId, server.getName());

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
    }

    /**
     * Deletes a saved connection (configuration and encrypted token). A server with a live
     * session must be disconnected first: removing it underneath a running investigation would
     * fail that run in a confusing way. Investigation history keeps its records; they simply
     * refer to a connection that no longer exists.
     */
    public void remove(UUID serverId) {
        synchronized (lockFor(serverId)) {
            EMcpServer server = findServer(serverId);
            if (clientRegistry.isConnected(serverId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "'" + server.getName() + "' is still connected. Disconnect it before removing it.");
            }
            repository.delete(server);
            log.info("MCP server removed: serverId={}, name={}", serverId, server.getName());
        }
        locks.remove(serverId);
    }

    public McpConnectionResponse status(UUID serverId) {
        EMcpServer server = findServer(serverId);
        boolean connected = clientRegistry.isConnected(serverId);

        McpConnectionStatus status = server.getStatus();
        String error = server.getLastError();

        if (connected) {
            status = McpConnectionStatus.CONNECTED;
        } else if (status == McpConnectionStatus.CONNECTED) {
            // Persisted state claims a session that does not exist in this process.
            server.markStale(STALE_SESSION_MESSAGE);
            repository.save(server);
            status = server.getStatus();
            error = server.getLastError();
        }

        return new McpConnectionResponse(
                serverId,
                status,
                connected,
                null,
                null,
                null,
                server.getLastConnectedAt(),
                error);
    }

    private Object lockFor(UUID serverId) {
        return locks.computeIfAbsent(serverId, id -> new Object());
    }

    private EMcpServer findServer(UUID serverId) {
        return repository.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP server " + serverId + " not found"));
    }

    private static void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (Exception closeException) {
            log.debug("Ignoring error while closing MCP client: {}", closeException.toString());
        }
    }

    private static boolean isConfigurationError(Exception exception) {
        return exception instanceof IllegalArgumentException
                || exception instanceof UnsupportedOperationException;
    }

    /**
     * Converts SDK/transport exceptions into a short, actionable and secret-free message.
     * The full stack trace is only logged at DEBUG.
     */
    static String describeFailure(Throwable exception) {
        Throwable root = exception;
        for (Throwable t = exception; t != null; t = t.getCause()) {
            root = t;
            if (t instanceof UnknownHostException) {
                return "Unknown host " + t.getMessage() + ". Check the endpoint URL.";
            }
            if (t instanceof HttpConnectTimeoutException) {
                return "Timed out connecting to the MCP server. Check the URL and network.";
            }
            if (t instanceof ConnectException) {
                return "Connection refused. Is the MCP server running at this endpoint?";
            }
            if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
                return "The MCP server did not respond in time.";
            }
            String message = t.getMessage();
            if (message != null && (message.contains("401") || message.contains("Unauthorized"))) {
                return "The MCP server rejected the credentials (HTTP 401). Check the token.";
            }
            if (message != null && (message.contains("403") || message.contains("Forbidden"))) {
                return "The MCP server refused access (HTTP 403). Check the token's permissions.";
            }
            if (message != null && message.contains("404")) {
                return "The MCP endpoint was not found (HTTP 404). Check the endpoint path.";
            }
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***");
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }

    private static String safeEndpointHost(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return "<invalid-endpoint>";
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
