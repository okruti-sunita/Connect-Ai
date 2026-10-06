package com.connectai.mcp.api.dto;

import java.util.Map;

public record McpToolExecutionRequest(
        Map<String, Object> arguments
) {
}
