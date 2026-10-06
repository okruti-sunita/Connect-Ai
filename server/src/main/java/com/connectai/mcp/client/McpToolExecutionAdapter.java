package com.connectai.mcp.client;

import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.agent.ToolExecutionRequest;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.mcp.api.dto.McpToolExecutionRequest;
import com.connectai.mcp.api.dto.McpToolExecutionResponse;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP implementation of the agent's transport-neutral tool execution port.
 *
 * The adapter is the only agent-facing class that knows MCP DTOs. The
 * Orchestrator therefore remains independent of the MCP SDK and transport.
 */
@Component
@RequiredArgsConstructor
public class McpToolExecutionAdapter implements ToolExecutionPort {

    private final McpToolExecutionService executionService;

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        McpToolExecutionResponse response = executionService.execute(
                request.serverId(),
                request.toolName(),
                new McpToolExecutionRequest(request.arguments()));

        return new ToolExecutionResult(
                response.toolName(),
                response.isError(),
                toContent(response.content()),
                response.structuredContent());
    }

    private List<ToolExecutionContent> toContent(
            List<McpSchema.Content> content) {

        if (content == null) {
            return List.of();
        }

        return content.stream()
                .map(this::toContent)
                .toList();
    }

    private ToolExecutionContent toContent(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent textContent) {
            return new ToolExecutionContent("text", textContent.text());
        }

        return new ToolExecutionContent(
                content == null ? "unknown" : content.getClass().getSimpleName(),
                null);
    }
}
