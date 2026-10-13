package com.connectai.mcp.service;

import com.connectai.mcp.api.dto.CreateMcpConnectionRequest;
import com.connectai.mcp.api.dto.McpConnectionTestResponse;
import com.connectai.mcp.catalog.ConnectMethod;
import com.connectai.mcp.catalog.McpCatalogService;
import com.connectai.mcp.client.McpClientFactory;
import com.connectai.mcp.client.McpConnectionService;
import com.connectai.mcp.client.McpToolDiscoveryService;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogConnectionServiceTest {
    @Test
    void testInitializesAndDiscoversPagesWithoutPersistingConnection() {
        McpCatalogService catalog = mock(McpCatalogService.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpConnectionService connections = mock(McpConnectionService.class);
        McpToolDiscoveryService discovery = mock(McpToolDiscoveryService.class);
        McpServerRepository repository = mock(McpServerRepository.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        McpSyncClient client = mock(McpSyncClient.class);
        when(encryption.encrypt(any())).thenReturn("ciphertext");
        when(factory.create(any(EMcpServer.class))).thenReturn(client);
        when(client.initialize()).thenReturn(new McpSchema.InitializeResult(
                "2025-03-26", mock(McpSchema.ServerCapabilities.class),
                new McpSchema.Implementation("fake-mcp", "1"), null));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("first")), "cursor-a", Map.of()));
        when(client.listTools("cursor-a")).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("second")), null, Map.of()));

        CatalogConnectionService service = new CatalogConnectionService(catalog, factory, connections,
                discovery, repository, encryption, new ObjectMapper());
        CreateMcpConnectionRequest request = new CreateMcpConnectionRequest(null, "Fake server", null,
                "https://mcp.example.test/mcp", ConnectMethod.TOKEN_BEARER, Map.of("token", "test-only"));

        McpConnectionTestResponse response = service.test(request);

        assertTrue(response.success());
        assertEquals(List.of("first", "second"), response.toolNames());
        assertEquals(2, response.toolCount());
        verify(client).closeGracefully();
        verify(repository, never()).save(any());
        verifyNoInteractions(connections, discovery);
    }

    @Test
    void testReportsHandshakeFailureAndDoesNotPersist() {
        McpCatalogService catalog = mock(McpCatalogService.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpConnectionService connections = mock(McpConnectionService.class);
        McpToolDiscoveryService discovery = mock(McpToolDiscoveryService.class);
        McpServerRepository repository = mock(McpServerRepository.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        McpSyncClient client = mock(McpSyncClient.class);
        when(factory.create(any(EMcpServer.class))).thenReturn(client);

        CatalogConnectionService service = new CatalogConnectionService(catalog, factory, connections,
                discovery, repository, encryption, new ObjectMapper());
        CreateMcpConnectionRequest request = new CreateMcpConnectionRequest(null, "Fake server", null,
                "https://mcp.example.test/mcp", ConnectMethod.NONE, Map.of());

        McpConnectionTestResponse response = service.test(request);

        assertFalse(response.success());
        assertEquals("CONNECTION_FAILED", response.code());
        verify(client).closeGracefully();
        verify(repository, never()).save(any());
        verifyNoInteractions(connections, discovery);
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object")).build();
    }
}
