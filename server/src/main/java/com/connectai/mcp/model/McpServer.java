package com.connectai.mcp.model;

import com.connectai.mcp.entity.EMcpServer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record McpServer(
        UUID id,
        String name,
        McpTransportType transport,
        String endpoint,
        McpAuthType authType,
        boolean enabled,
        McpConnectionStatus status,
        String lastError,
        OffsetDateTime lastConnectedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean runtimeConnected,
        boolean secretConfigured,
        /** OAuth provider id for authType OAUTH; null otherwise. secretConfigured then means "signed in". */
        String oauthProvider
) {

    public static McpServer from(EMcpServer entity) {
        return from(entity, false);
    }

    /**
     * {@code runtimeConnected} is the live truth (a handshaken session exists in this process);
     * {@code status} is the persisted lifecycle state. The secret itself is never exposed.
     */
    public static McpServer from(EMcpServer entity, boolean runtimeConnected) {
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
                entity.getUpdatedAt(),
                runtimeConnected,
                entity.getEncryptedSecret() != null && !entity.getEncryptedSecret().isBlank(),
                entity.getOauthProvider()
        );
    }
}