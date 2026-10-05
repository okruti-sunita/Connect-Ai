package com.connectai.mcp.entity;

import com.connectai.jpa.base.AbstractUUIDPersistable;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.model.McpTransportType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "mcp_server")
@Getter
@NoArgsConstructor
public class EMcpServer extends AbstractUUIDPersistable {

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private McpTransportType transport;

    @Column(nullable = false, length = 500)
    private String endpoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private McpAuthType authType;

    @Column(name = "encrypted_secret", length = 2000)
    private String encryptedSecret;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private McpConnectionStatus status;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "last_connected_at")
    private OffsetDateTime lastConnectedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public EMcpServer(
            String name,
            McpTransportType transport,
            String endpoint,
            McpAuthType authType,
            String encryptedSecret,
            boolean enabled
    ) {
        this.name = name;
        this.transport = transport;
        this.endpoint = endpoint;
        this.authType = authType;
        this.encryptedSecret = encryptedSecret;
        this.enabled = enabled;
        this.status = McpConnectionStatus.REGISTERED;
    }

    public void markConnecting() {
        this.status = McpConnectionStatus.CONNECTING;
        this.lastError = null;
    }

    public void markConnected() {
        this.status = McpConnectionStatus.CONNECTED;
        this.lastError = null;
        this.lastConnectedAt = OffsetDateTime.now();
    }

    public void markFailed(String error) {
        this.status = McpConnectionStatus.FAILED;
        this.lastError = error;
    }

    public void markDisconnected() {
        this.status = McpConnectionStatus.DISCONNECTED;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}