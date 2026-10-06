package com.connectai.mcp.api.dto;

import java.util.Map;

public record McpDiscoveredTool(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
) {
}
