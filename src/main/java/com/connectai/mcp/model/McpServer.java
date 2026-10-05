package com.connectai.mcp.model;

import com.connectai.mcp.entity.EMcpServer;

import java.time.OffsetDateTime;

public record McpServer(
        Long id,
        String name,
        McpTransportType transport,
        String endpoint,
        McpAuthType authType,
        boolean enabled,
        McpConnectionStatus status,
        String lastError,
        OffsetDateTime lastConnectedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static McpServer from(EMcpServer entity) {
        return new McpServer(
                entity.getId(),
                entity.getName(),
                entity.getTransport(),
                entity.getEndpoint(),
                entity.getAuthType(),
                entity.isEnabled(),
                entity.getStatus(),
                entity.getLastError(),
                entity.getLastConnectedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
