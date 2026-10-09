package com.connectai.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
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
public class McpClientRegistry {

    private final Map<UUID, McpSyncClient> clients = new ConcurrentHashMap<>();

    public void register(UUID serverId, McpSyncClient client) {
        McpSyncClient previous = clients.put(serverId, client);

        if (previous != null && previous != client) {
            previous.closeGracefully();
        }
    }

    public Optional<McpSyncClient> find(UUID serverId) {
        return Optional.ofNullable(clients.get(serverId));
    }

    public McpSyncClient require(UUID serverId) {
        return find(serverId)
                .orElseThrow(() -> new IllegalStateException(
                        "MCP server " + serverId + " is not connected"));
    }

    public Optional<McpSyncClient> remove(UUID serverId) {
        return Optional.ofNullable(clients.remove(serverId));
    }

    public boolean isConnected(UUID serverId) {
        return clients.containsKey(serverId);
    }

    public Set<UUID> connectedServerIds() {
        return Set.copyOf(clients.keySet());
    }
}