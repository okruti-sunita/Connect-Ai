package com.connectai.mcp.api.dto;

import java.util.List;
import java.util.UUID;

public record McpToolDiscoveryResponse(
        UUID serverId,
        List<McpDiscoveredTool> tools
) {
}
