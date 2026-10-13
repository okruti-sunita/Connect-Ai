package com.connectai.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of live MCP client sessions.
 *
 * Database registration and runtime connection state are deliberately
 * kept separate.
 */
@Component
@Slf4j
public class McpClientRegistry {

    private final Map<UUID, McpSyncClient> clients = new ConcurrentHashMap<>();

    public void register(UUID serverId, McpSyncClient client) {
        McpSyncClient previous = clients.put(serverId, client);
        log.info("MCP runtime client registered: serverId={}, connectedRuntimeServers={}", serverId, clients.size());

        if (previous != null && previous != client) {
            try {
                previous.closeGracefully();
            } catch (RuntimeException exception) {
                log.debug("Ignoring error while closing replaced MCP client: serverId={}, exceptionType={}",
                        serverId, exception.getClass().getSimpleName());
            }
        }
    }

    /** Closes every live session on application shutdown. */
    @PreDestroy
    public void closeAll() {
        clients.forEach((serverId, client) -> {
            try {
                client.closeGracefully();
            } catch (RuntimeException exception) {
                log.debug("Ignoring error while closing MCP client on shutdown: serverId={}", serverId);
            }
        });
        clients.clear();
    }

    public Optional<McpSyncClient> find(UUID serverId) {
        return Optional.ofNullable(clients.get(serverId));
    }

    public McpSyncClient require(UUID serverId) {
        return find(serverId)
                .orElseThrow(() -> new IllegalStateException(
                        "MCP server " + serverId + " is not connected. Connect it on the Connections page first."));
    }

    public Optional<McpSyncClient> remove(UUID serverId) {
        Optional<McpSyncClient> removed = Optional.ofNullable(clients.remove(serverId));
        log.info("MCP runtime client removed: serverId={}, connectedRuntimeServers={}", serverId, clients.size());
        return removed;
    }

    public boolean isConnected(UUID serverId) {
        return clients.containsKey(serverId);
    }

    public Set<UUID> connectedServerIds() {
        return Set.copyOf(clients.keySet());
    }
}
