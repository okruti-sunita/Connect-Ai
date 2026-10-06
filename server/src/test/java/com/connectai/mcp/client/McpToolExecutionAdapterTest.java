package com.connectai.mcp.client;

import com.connectai.agent.ToolExecutionRequest;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.mcp.api.dto.McpToolExecutionResponse;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolExecutionAdapterTest {

    private final McpToolExecutionService executionService =
            mock(McpToolExecutionService.class);

    private final McpToolExecutionAdapter adapter =
            new McpToolExecutionAdapter(executionService);

    @Test
    void executeTranslatesAgentRequestToMcpServiceAndMapsTextResult() {
        UUID serverId = UUID.randomUUID();

        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .addTextContent("created")
                        .build();

        when(executionService.execute(
                eq(serverId),
                eq("create_issue"),
                eq(new com.connectai.mcp.api.dto.McpToolExecutionRequest(
                        Map.of("title", "Payment failure")))))
                .thenReturn(McpToolExecutionResponse.from(
                        serverId,
                        "create_issue",
                        mcpResult));

        ToolExecutionResult result = adapter.execute(
                new ToolExecutionRequest(
                        serverId,
                        "create_issue",
                        Map.of("title", "Payment failure")));

        assertThat(result.toolName()).isEqualTo("create_issue");
        assertThat(result.error()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).type()).isEqualTo("text");
        assertThat(result.content().get(0).text()).isEqualTo("created");

        verify(executionService).execute(
                eq(serverId),
                eq("create_issue"),
                eq(new com.connectai.mcp.api.dto.McpToolExecutionRequest(
                        Map.of("title", "Payment failure"))));
    }

    @Test
    void executePreservesMcpToolErrorWithoutConvertingItToTransportFailure() {
        UUID serverId = UUID.randomUUID();

        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .addTextContent("issue already exists")
                        .isError(true)
                        .build();

        when(executionService.execute(
                eq(serverId),
                eq("create_issue"),
                eq(new com.connectai.mcp.api.dto.McpToolExecutionRequest(Map.of()))))
                .thenReturn(McpToolExecutionResponse.from(
                        serverId,
                        "create_issue",
                        mcpResult));

        ToolExecutionResult result = adapter.execute(
                new ToolExecutionRequest(
                        serverId,
                        "create_issue",
                        Map.of()));

        assertThat(result.error()).isTrue();
        assertThat(result.content().get(0).text())
                .isEqualTo("issue already exists");
    }
}
