package com.connectai.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpClientRegistryTest {

    @Test
    void replacesExistingClientAndClosesPreviousClient() {
        McpClientRegistry registry = new McpClientRegistry();

        McpSyncClient first = mock(McpSyncClient.class);
        McpSyncClient second = mock(McpSyncClient.class);

        UUID serverId = UUID.randomUUID();

        registry.register(serverId, first);
        registry.register(serverId, second);

        verify(first).closeGracefully();

        assertSame(second, registry.require(serverId));
        assertTrue(registry.isConnected(serverId));
    }
}