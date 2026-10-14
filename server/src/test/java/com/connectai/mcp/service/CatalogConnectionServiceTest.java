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

    @Test
    void reportsPermissionFailureWithoutEchoingUpstreamDetails() {
        McpCatalogService catalog = mock(McpCatalogService.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        when(factory.create(any(EMcpServer.class)))
                .thenThrow(new IllegalStateException("HTTP 403 Forbidden token=must-not-be-shown"));
        CatalogConnectionService service = new CatalogConnectionService(catalog, factory,
                mock(McpConnectionService.class), mock(McpToolDiscoveryService.class),
                mock(McpServerRepository.class), mock(SecretEncryptionService.class), new ObjectMapper());
        CreateMcpConnectionRequest request = new CreateMcpConnectionRequest(null, "Grafana", null,
                "https://mcp.example.test/mcp", ConnectMethod.NONE, Map.of());

        McpConnectionTestResponse response = service.test(request);

        assertFalse(response.success());
        assertTrue(response.message().contains("HTTP 403"));
        assertTrue(response.message().contains("Grafana service-account RBAC"));
        assertFalse(response.message().contains("must-not-be-shown"));
    }

    @Test
    void testTreatsAnEmptyToolListAsAnUnusableMcpConnection() {
        McpCatalogService catalog = mock(McpCatalogService.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpConnectionService connections = mock(McpConnectionService.class);
        McpToolDiscoveryService discovery = mock(McpToolDiscoveryService.class);
        McpServerRepository repository = mock(McpServerRepository.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        McpSyncClient client = mock(McpSyncClient.class);
        when(factory.create(any(EMcpServer.class))).thenReturn(client);
        when(client.initialize()).thenReturn(new McpSchema.InitializeResult(
                "2025-03-26", mock(McpSchema.ServerCapabilities.class),
                new McpSchema.Implementation("empty-mcp", "1"), null));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null, Map.of()));

        CatalogConnectionService service = new CatalogConnectionService(catalog, factory, connections,
                discovery, repository, encryption, new ObjectMapper());
        CreateMcpConnectionRequest request = new CreateMcpConnectionRequest(null, "Empty server", null,
                "https://mcp.example.test/mcp", ConnectMethod.NONE, Map.of());

        McpConnectionTestResponse response = service.test(request);

        assertFalse(response.success());
        assertEquals("EMPTY_TOOL_LIST", response.code());
        assertEquals(0, response.toolCount());
        assertTrue(response.message().contains("returned no tools"));
        verify(client).closeGracefully();
        verify(repository, never()).save(any());
    }

    @Test
    void grafanaConnectionEncryptsItsDocumentedHeadersBeforeMcpHandshake() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        McpCatalogService catalog = mock(McpCatalogService.class);
        when(catalog.definition("grafana")).thenReturn(mapper.readTree("""
                {"methods":[{"id":"TOKEN_HEADERS","supported":true}]}
                """));
        McpClientFactory factory = mock(McpClientFactory.class);
        McpConnectionService connections = mock(McpConnectionService.class);
        McpToolDiscoveryService discovery = mock(McpToolDiscoveryService.class);
        McpServerRepository repository = mock(McpServerRepository.class);
        SecretEncryptionService encryption = new SecretEncryptionService("grafana-test-key-long-enough-for-encryption");
        McpSyncClient client = mock(McpSyncClient.class);
        when(factory.create(any(EMcpServer.class))).thenReturn(client);
        when(client.initialize()).thenReturn(new McpSchema.InitializeResult(
                "2025-03-26", mock(McpSchema.ServerCapabilities.class),
                new McpSchema.Implementation("grafana-mcp", "1"), null));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("search_dashboards")), null, Map.of()));
        CatalogConnectionService service = new CatalogConnectionService(catalog, factory, connections,
                discovery, repository, encryption, mapper);
        Map<String, String> auth = Map.of(
                "header:X-Grafana-URL", "https://8.8.8.8/grafana",
                "header:X-Grafana-Service-Account-Token", "sensitive-grafana-token",
                "header:Authorization", "Bearer endpoint-token");
        CreateMcpConnectionRequest request = new CreateMcpConnectionRequest("grafana", "Grafana - Test", null,
                "https://mcp.example.test/mcp", ConnectMethod.TOKEN_HEADERS, auth);

        McpConnectionTestResponse result = service.test(request);

        assertTrue(result.success());
        assertEquals(List.of("search_dashboards"), result.toolNames());
        org.mockito.ArgumentCaptor<EMcpServer> captor = org.mockito.ArgumentCaptor.forClass(EMcpServer.class);
        verify(factory).create(captor.capture());
        String encrypted = captor.getValue().getEncryptedAuthConfig();
        org.junit.jupiter.api.Assertions.assertFalse(encrypted.contains("sensitive-grafana-token"));
        assertEquals(auth, mapper.readValue(encryption.decrypt(encrypted), new com.fasterxml.jackson.core.type.TypeReference<>() { }));
        verify(client).closeGracefully();
    }

    @Test
    void grafanaRejectsMetadataAndPrivateTargetsWithoutPrivateNetworkOptIn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        McpCatalogService catalog = mock(McpCatalogService.class);
        when(catalog.definition("grafana")).thenReturn(mapper.readTree("""
                {"methods":[{"id":"TOKEN_HEADERS","supported":true}]}
                """));
        McpClientFactory factory = mock(McpClientFactory.class);
        CatalogConnectionService service = new CatalogConnectionService(catalog, factory,
                mock(McpConnectionService.class), mock(McpToolDiscoveryService.class),
                mock(McpServerRepository.class), mock(SecretEncryptionService.class), mapper);
        Map<String, String> credentials = Map.of("header:X-Grafana-URL", "https://169.254.169.254",
                "header:X-Grafana-Service-Account-Token", "test-token");
        CreateMcpConnectionRequest request = new CreateMcpConnectionRequest("grafana", "Grafana - Unsafe", null,
                "https://mcp.example.test/mcp", ConnectMethod.TOKEN_HEADERS, credentials);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> service.test(request));
        verifyNoInteractions(factory);
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object")).build();
    }
}
