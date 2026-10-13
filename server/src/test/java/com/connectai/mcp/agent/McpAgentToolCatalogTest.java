package com.connectai.mcp.agent;

import com.connectai.agent.McpToolCandidate;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAgentToolCatalogTest {

    private final UUID serverId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private McpServerRepository repository;
    private McpClientRegistry registry;
    private McpSyncClient client;
    private McpAgentToolCatalog catalog;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerRepository.class);
        registry = new McpClientRegistry();
        client = mock(McpSyncClient.class);
        catalog = new McpAgentToolCatalog(repository, registry, true);

        EMcpServer server = new EMcpServer(
                "test-server", McpTransportType.STREAMABLE_HTTP, "http://localhost:9999/mcp",
                McpAuthType.NONE, null, true);
        server.markConnected();
        when(repository.findById(serverId)).thenReturn(Optional.of(server));
        registry.register(serverId, client);
    }

    @Test
    void collectsToolsAcrossNormalMultiplePages() {
        when(client.listTools((String) null)).thenReturn(page("first_tool", "cursor-A"));
        when(client.listTools("cursor-A")).thenReturn(page("second_tool", "cursor-B"));
        when(client.listTools("cursor-B")).thenReturn(page("third_tool", null));

        List<McpToolCandidate> tools = catalog.getAvailableTools();

        assertThat(tools).extracting(McpToolCandidate::toolName)
                .containsExactly("first_tool", "second_tool", "third_tool");
        verify(client).listTools((String) null);
        verify(client).listTools("cursor-A");
        verify(client).listTools("cursor-B");
    }

    @Test
    void stopsNormallyWhenThereIsNoNextCursor() {
        when(client.listTools((String) null)).thenReturn(page("only_tool", null));

        assertThat(catalog.getAvailableTools()).extracting(McpToolCandidate::toolName)
                .containsExactly("only_tool");

        verify(client, times(1)).listTools((String) null);
    }

    @Test
    void aConnectedServerThatSuccessfullyReturnsNoToolsProducesAnEmptyCatalog() {
        when(client.listTools((String) null)).thenReturn(new McpSchema.ListToolsResult(List.of(), null, Map.of()));

        assertThat(catalog.getAvailableTools()).isEmpty();
    }

    @Test
    void discoveryFailureIsNotReportedAsAnEmptyCatalog() {
        when(client.listTools((String) null)).thenThrow(new IllegalStateException("remote failure"));

        assertThatThrownBy(catalog::getAvailableTools)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP tool discovery failed")
                .hasMessageContaining(serverId.toString());
    }

    @Test
    void toolsCollectedBeforeALaterPageFailureRemainAvailable() {
        when(client.listTools((String) null)).thenReturn(page("first_tool", "next"));
        when(client.listTools("next")).thenThrow(new IllegalStateException("remote failure"));

        assertThat(catalog.getAvailableTools()).extracting(McpToolCandidate::toolName)
                .containsExactly("first_tool");
    }

    @Test
    void partialServerDiscoveryFailureKeepsOtherToolsAndReturnsWarning() {
        UUID healthyId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        McpSyncClient healthyClient = mock(McpSyncClient.class);
        EMcpServer healthy = new EMcpServer(
                "healthy-server", McpTransportType.STREAMABLE_HTTP, "http://localhost:9998/mcp",
                McpAuthType.NONE, null, true);
        healthy.markConnected();
        when(repository.findById(healthyId)).thenReturn(Optional.of(healthy));
        registry.register(healthyId, healthyClient);
        when(client.listTools((String) null)).thenThrow(new IllegalStateException("private remote detail"));
        when(healthyClient.listTools((String) null)).thenReturn(page("healthy_tool", null));

        var snapshot = catalog.snapshot();

        assertThat(snapshot.tools()).extracting(McpToolCandidate::toolName).containsExactly("healthy_tool");
        assertThat(snapshot.warnings()).singleElement().asString()
                .contains("discovery failed").contains("may be incomplete")
                .doesNotContain("private remote detail");
    }

    @Test
    void stopsAfterAnImmediatelyRepeatedCursorAndKeepsCollectedTools() {
        when(client.listTools((String) null)).thenReturn(page("first_tool", "same"));
        when(client.listTools("same")).thenReturn(page("second_tool", "same"));

        assertThat(catalog.getAvailableTools()).extracting(McpToolCandidate::toolName)
                .containsExactly("first_tool", "second_tool");

        verify(client, times(1)).listTools((String) null);
        verify(client, times(1)).listTools("same");
    }

    @Test
    void detectsMultiCursorCycleWithoutRequestingTheRepeatedCursorAgain() {
        when(client.listTools((String) null)).thenReturn(page("first_tool", "A"));
        AtomicInteger requestsForA = new AtomicInteger();
        when(client.listTools("A")).thenAnswer(invocation -> {
            if (requestsForA.incrementAndGet() > 1) {
                throw new AssertionError("catalog requested cursor A more than once");
            }
            return page("second_tool", "B");
        });
        when(client.listTools("B")).thenReturn(page("third_tool", "A"));

        assertThat(catalog.getAvailableTools()).extracting(McpToolCandidate::toolName)
                .containsExactly("first_tool", "second_tool", "third_tool");

        assertThat(requestsForA).hasValue(1);
        verify(client, times(1)).listTools("A");
        verify(client, times(1)).listTools("B");
    }

    private McpSchema.ListToolsResult page(String toolName, String nextCursor) {
        return new McpSchema.ListToolsResult(List.of(tool(toolName)), nextCursor, Map.of());
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object"))
                .title(name)
                .description("Read-only test tool")
                .build();
    }
}
