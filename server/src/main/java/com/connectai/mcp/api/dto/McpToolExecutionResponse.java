package com.connectai.mcp.api.dto;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.UUID;

public record McpToolExecutionResponse(
        UUID serverId,
        String toolName,
        boolean isError,
        List<McpSchema.Content> content,
        Object structuredContent
) {

    public static McpToolExecutionResponse from(
            UUID serverId,
            String toolName,
            McpSchema.CallToolResult result) {

        return new McpToolExecutionResponse(
                serverId,
                toolName,
                Boolean.TRUE.equals(result.isError()),
                result.content(),
                result.structuredContent());
    }
}
