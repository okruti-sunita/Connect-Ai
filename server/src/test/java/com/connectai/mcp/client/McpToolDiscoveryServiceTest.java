package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpToolDiscoveryResponse;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolDiscoveryServiceTest {

    private McpServerRepository repository;
    private McpClientRegistry registry;
    private McpSyncClient client;
    private McpToolDiscoveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerRepository.class);
        registry = new McpClientRegistry();
        client = mock(McpSyncClient.class);
        service = new McpToolDiscoveryService(repository, registry);
    }

    @Test
    void discoversToolsFromConnectedServer() {
        UUID serverId = UUID.randomUUID();
        EMcpServer server = server(serverId);
        McpSchema.Tool tool = tool(
                "create_issue",
                "Create Issue",
                "Creates an issue",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string"))));

        when(repository.findById(serverId)).thenReturn(Optional.of(server));
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tool), null, Map.of()));
        registry.register(serverId, client);

        McpToolDiscoveryResponse response = service.discover(serverId);

        assertEquals(serverId, response.serverId());
        assertEquals(1, response.tools().size());
        assertEquals("create_issue", response.tools().get(0).name());
        assertEquals("Create Issue", response.tools().get(0).title());
        assertEquals("Creates an issue", response.tools().get(0).description());
        assertEquals("object", response.tools().get(0).inputSchema().get("type"));
        verify(client).listTools();
    }

    @Test
    void discoversAllPagesWhenServerReturnsCursor() {
        UUID serverId = UUID.randomUUID();
        EMcpServer server = server(serverId);
        McpSchema.Tool first = tool("first_tool", "First", "First tool", Map.of());
        McpSchema.Tool second = tool("second_tool", "Second", "Second tool", Map.of());

        when(repository.findById(serverId)).thenReturn(Optional.of(server));
        when(client.listTools())
                .thenReturn(new McpSchema.ListToolsResult(List.of(first), "cursor-1", Map.of()));
        when(client.listTools("cursor-1"))
                .thenReturn(new McpSchema.ListToolsResult(List.of(second), null, Map.of()));
        registry.register(serverId, client);

        McpToolDiscoveryResponse response = service.discover(serverId);

        assertEquals(2, response.tools().size());
        assertEquals("first_tool", response.tools().get(0).name());
        assertEquals("second_tool", response.tools().get(1).name());
        verify(client).listTools();
        verify(client).listTools("cursor-1");
    }

    @Test
    void rejectsDiscoveryWhenServerDoesNotExist() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.empty());

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.discover(serverId));

        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test
    void rejectsDiscoveryWhenServerIsNotConnected() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.discover(serverId));

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("is not connected"));
        verifyNoInteractions(client);
    }

    @Test
    void convertsMcpFailureToBadGateway() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));
        when(client.listTools()).thenThrow(new RuntimeException("remote MCP failure"));
        registry.register(serverId, client);

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.discover(serverId));

        assertEquals(502, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("MCP tool discovery failed"));
    }

    @Test
    void rejectsRepeatedPaginationCursor() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));
        when(client.listTools())
                .thenReturn(new McpSchema.ListToolsResult(List.of(), "same", Map.of()));
        when(client.listTools("same"))
                .thenReturn(new McpSchema.ListToolsResult(List.of(), "same", Map.of()));
        registry.register(serverId, client);

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.discover(serverId));

        assertEquals(502, exception.getStatusCode().value());
    }

    private EMcpServer server(UUID ignoredId) {
        return new EMcpServer(
                "test-server",
                McpTransportType.STREAMABLE_HTTP,
                "http://localhost:9999/mcp",
                McpAuthType.NONE,
                null,
                true);
    }

    private McpSchema.Tool tool(
            String name,
            String title,
            String description,
            Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder(name, inputSchema)
                .title(title)
                .description(description)
                .build();
    }
}
