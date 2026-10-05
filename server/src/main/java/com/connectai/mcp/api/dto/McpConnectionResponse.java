package com.connectai.mcp.api.dto;

import com.connectai.mcp.model.McpConnectionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record McpConnectionResponse(
        UUID serverId,
        McpConnectionStatus status,
        boolean initialized,
        String protocolVersion,
        String serverName,
        String serverVersion,
        OffsetDateTime connectedAt,
        String error
) {}
