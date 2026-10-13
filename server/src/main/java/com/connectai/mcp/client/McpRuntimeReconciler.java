package com.connectai.mcp.client;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Live MCP sessions do not survive a restart, so on startup any server persisted as
 * CONNECTED or CONNECTING is marked DISCONNECTED with an explanation; the application never
 * claims a stored connection is live.
 *
 * With {@code connectai.mcp.reconnect-on-startup=true} those servers are then reconnected in
 * the background with a real handshake. Each one either becomes CONNECTED or FAILED with its
 * error; one failing server does not stop the others.
 */
@Component
@Slf4j
public class McpRuntimeReconciler {

    private final McpServerRepository repository;
    private final McpClientRegistry registry;
    private final McpConnectionService connections;
    private final boolean reconnectOnStartup;

    public McpRuntimeReconciler(
            McpServerRepository repository,
            McpClientRegistry registry,
            McpConnectionService connections,
            @Value("${connectai.mcp.reconnect-on-startup:false}") boolean reconnectOnStartup) {
        this.repository = repository;
        this.registry = registry;
        this.connections = connections;
        this.reconnectOnStartup = reconnectOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        List<EMcpServer> stale = repository.findAll().stream()
                .filter(server -> server.getStatus() == McpConnectionStatus.CONNECTED
                        || server.getStatus() == McpConnectionStatus.CONNECTING)
                .filter(server -> !registry.isConnected(server.getId()))
                .toList();

        stale.forEach(server -> server.markStale(McpConnectionService.STALE_SESSION_MESSAGE));
        repository.saveAll(stale);

        if (stale.isEmpty()) {
            return;
        }
        log.info("Marked {} MCP server(s) DISCONNECTED at startup because no live session exists: {}",
                stale.size(), stale.stream().map(EMcpServer::getName).toList());

        if (reconnectOnStartup) {
            List<UUID> ids = stale.stream().filter(EMcpServer::isEnabled).map(EMcpServer::getId).toList();
            Thread reconnect = new Thread(() -> reconnectAll(ids), "mcp-reconnect");
            reconnect.setDaemon(true);
            reconnect.start();
        }
    }

    private void reconnectAll(List<UUID> ids) {
        for (UUID id : ids) {
            try {
                connections.connect(id);
                log.info("Reconnected MCP server at startup: serverId={}", id);
            } catch (RuntimeException exception) {
                // connect() has already stored FAILED and the reason on the server.
                log.warn("Startup reconnect failed: serverId={}, reason={}", id,
                        McpConnectionService.describeFailure(exception));
            }
        }
    }
}
