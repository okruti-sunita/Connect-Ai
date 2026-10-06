package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpToolExecutionRequest;
import com.connectai.mcp.api.dto.McpToolExecutionResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpToolExecutionServiceTest {

    private McpServerRepository repository;
    private McpClientRegistry registry;
    private McpSyncClient client;
    private McpToolExecutionService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerRepository.class);
        registry = new McpClientRegistry();
        client = mock(McpSyncClient.class);
        service = new McpToolExecutionService(repository, registry);
    }

    @Test
    void executesDiscoveredToolWithArguments() {
        UUID serverId = UUID.randomUUID();
        EMcpServer server = server(serverId);
        Map<String, Object> arguments = Map.of("message", "hello");
        McpSchema.Tool tool = tool("echo", "Echo", "Echoes a message");
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent("hello")
                .build();

        when(repository.findById(serverId)).thenReturn(Optional.of(server));
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tool), null, Map.of()));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(result);
        registry.register(serverId, client);

        McpToolExecutionResponse response = service.execute(
                serverId,
                "echo",
                new McpToolExecutionRequest(arguments));

        assertEquals(serverId, response.serverId());
        assertEquals("echo", response.toolName());
        assertFalse(response.isError());
        assertEquals(1, response.content().size());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(requestCaptor.capture());
        assertEquals("echo", requestCaptor.getValue().name());
        assertEquals(arguments, requestCaptor.getValue().arguments());
    }

    @Test
    void returnsToolLevelErrorWithoutConvertingItToHttpFailure() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tool("echo", "Echo", "Echoes")), null, Map.of()));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                McpSchema.CallToolResult.builder()
                        .addTextContent("tool rejected the input")
                        .isError(true)
                        .build());
        registry.register(serverId, client);

        McpToolExecutionResponse response = service.execute(
                serverId,
                "echo",
                new McpToolExecutionRequest(Map.of()));

        assertTrue(response.isError());
        assertEquals("tool rejected the input", ((McpSchema.TextContent) response.content().get(0)).text());
    }

    @Test
    void rejectsUnknownServer() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(serverId, "echo", new McpToolExecutionRequest(Map.of())));

        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test
    void rejectsExecutionWhenServerIsNotConnected() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(serverId, "echo", new McpToolExecutionRequest(Map.of())));

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("is not connected"));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsToolThatIsNotExposedByServer() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tool("echo", "Echo", "Echoes")), null, Map.of()));
        registry.register(serverId, client);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(serverId, "delete_everything", new McpToolExecutionRequest(Map.of())));

        assertEquals(404, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("is not exposed"));
        verify(client, never()).callTool(any());
    }

    @Test
    void convertsMcpFailureToBadGateway() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tool("echo", "Echo", "Echoes")), null, Map.of()));
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenThrow(new RuntimeException("remote MCP failure"));
        registry.register(serverId, client);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(serverId, "echo", new McpToolExecutionRequest(Map.of())));

        assertEquals(502, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("MCP tool execution failed"));
    }

    @Test
    void handlesPaginatedToolCatalogBeforeExecution() {
        UUID serverId = UUID.randomUUID();
        when(repository.findById(serverId)).thenReturn(Optional.of(server(serverId)));
        when(client.listTools())
                .thenReturn(new McpSchema.ListToolsResult(
                        List.of(tool("first", "First", "First tool")), "next", Map.of()));
        when(client.listTools("next"))
                .thenReturn(new McpSchema.ListToolsResult(
                        List.of(tool("echo", "Echo", "Echoes")), null, Map.of()));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                McpSchema.CallToolResult.builder().addTextContent("hello").build());
        registry.register(serverId, client);

        McpToolExecutionResponse response = service.execute(
                serverId,
                "echo",
                new McpToolExecutionRequest(Map.of("message", "hello")));

        assertFalse(response.isError());
        verify(client).listTools();
        verify(client).listTools("next");
        verify(client).callTool(any(McpSchema.CallToolRequest.class));
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

    private McpSchema.Tool tool(String name, String title, String description) {
        return McpSchema.Tool.builder(name, Map.of("type", "object"))
                .title(title)
                .description(description)
                .build();
    }
}
