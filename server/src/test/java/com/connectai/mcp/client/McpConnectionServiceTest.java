package com.connectai.mcp.client;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpConnectionServiceTest {

    @Test
    void connectInitializesClientAndMarksServerConnected() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();
        McpSyncClient client = mock(McpSyncClient.class);

        McpSchema.InitializeResult initializeResult =
                mock(McpSchema.InitializeResult.class);

        McpSchema.Implementation serverInfo =
                mock(McpSchema.Implementation.class);

        when(serverInfo.name()).thenReturn("test-server");
        when(serverInfo.version()).thenReturn("1.0.0");
        when(initializeResult.protocolVersion()).thenReturn("2025-06-18");
        when(initializeResult.serverInfo()).thenReturn(serverInfo);
        when(client.initialize()).thenReturn(initializeResult);

        EMcpServer server = new EMcpServer(
                "test",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:8080/mcp",
                McpAuthType.NONE,
                null,
                true);

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId)).thenReturn(Optional.of(server));
        when(factory.create(server)).thenReturn(client);
        when(repository.save(server)).thenReturn(server);

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        var response = service.connect(serverId);

        assertEquals(McpConnectionStatus.CONNECTED, response.status());
        assertTrue(response.initialized());
        assertEquals("2025-06-18", response.protocolVersion());
        assertEquals("test-server", response.serverName());
        assertEquals("1.0.0", response.serverVersion());
        assertTrue(registry.isConnected(serverId));
        assertEquals(McpConnectionStatus.CONNECTED, server.getStatus());
        assertNotNull(server.getLastConnectedAt());

        verify(client).initialize();
    }

    @Test
    void connectMarksServerFailedWhenHandshakeThrows() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();
        McpSyncClient client = mock(McpSyncClient.class);

        EMcpServer server = new EMcpServer(
                "test",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:8080/mcp",
                McpAuthType.NONE,
                null,
                true);

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId)).thenReturn(Optional.of(server));
        when(factory.create(server)).thenReturn(client);
        when(client.initialize())
                .thenThrow(new RuntimeException("server rejected initialize"));
        when(repository.save(server)).thenReturn(server);

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        assertThrows(
                ResponseStatusException.class,
                () -> service.connect(serverId));

        assertEquals(
                McpConnectionStatus.FAILED,
                server.getStatus());

        assertTrue(
                server.getLastError()
                        .contains("server rejected initialize"));

        assertFalse(registry.isConnected(serverId));

        verify(client).closeGracefully();
    }

    @Test
    void disabledServerCannotBeConnected() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();

        EMcpServer server = new EMcpServer(
                "disabled",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:8080/mcp",
                McpAuthType.NONE,
                null,
                false);

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId))
                .thenReturn(Optional.of(server));

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.connect(serverId));

        assertEquals(409, exception.getStatusCode().value());

        assertEquals(
                McpConnectionStatus.REGISTERED,
                server.getStatus());

        verify(factory, org.mockito.Mockito.never())
                .create(server);
    }

    @Test
    void disconnectClosesClientAndMarksServerDisconnected() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();
        McpSyncClient client = mock(McpSyncClient.class);

        EMcpServer server = new EMcpServer(
                "test",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:8080/mcp",
                McpAuthType.NONE,
                null,
                true);

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId))
                .thenReturn(Optional.of(server));

        when(repository.save(server))
                .thenReturn(server);

        registry.register(serverId, client);

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        var response = service.disconnect(serverId);

        assertEquals(
                McpConnectionStatus.DISCONNECTED,
                response.status());

        assertFalse(response.initialized());

        assertEquals(
                McpConnectionStatus.DISCONNECTED,
                server.getStatus());

        assertFalse(registry.isConnected(serverId));

        verify(client).closeGracefully();
    }

    @Test
    void statusReturnsConnectedWhenRuntimeClientExists() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();
        McpSyncClient client = mock(McpSyncClient.class);

        EMcpServer server = new EMcpServer(
                "test",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:8080/mcp",
                McpAuthType.NONE,
                null,
                true);

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId))
                .thenReturn(Optional.of(server));

        registry.register(serverId, client);

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        var response = service.status(serverId);

        assertEquals(
                McpConnectionStatus.CONNECTED,
                response.status());

        assertTrue(response.initialized());
        assertTrue(registry.isConnected(serverId));
    }

    @Test
    void statusReturnsPersistedStatusWhenRuntimeClientDoesNotExist() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();

        EMcpServer server = new EMcpServer(
                "test",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:8080/mcp",
                McpAuthType.NONE,
                null,
                true);

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId))
                .thenReturn(Optional.of(server));

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        var response = service.status(serverId);

        assertEquals(
                McpConnectionStatus.REGISTERED,
                response.status());

        assertFalse(response.initialized());
        assertFalse(registry.isConnected(serverId));
    }

    @Test
    void connectFailsWhenServerDoesNotExist() {
        McpServerRepository repository = mock(McpServerRepository.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        McpClientRegistry registry = new McpClientRegistry();

        UUID serverId = UUID.randomUUID();

        when(repository.findById(serverId))
                .thenReturn(Optional.empty());

        McpConnectionService service =
                new McpConnectionService(repository, factory, registry);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.connect(serverId));

        assertEquals(404, exception.getStatusCode().value());
    }
}