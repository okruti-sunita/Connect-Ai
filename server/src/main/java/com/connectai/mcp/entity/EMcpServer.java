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

    /** OAuth provider id (connectai.oauth.providers key) when authType is OAUTH. */
    @Column(name = "oauth_provider", length = 50)
    private String oauthProvider;

    @Column(name = "encrypted_refresh_token", length = 4000)
    private String encryptedRefreshToken;

    /** Null when the provider issued a non-expiring token. */
    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

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

    public void useOAuthProvider(String providerId) {
        this.oauthProvider = providerId;
    }

    /** Stores a freshly issued OAuth token (already encrypted). The old one is replaced. */
    public void storeOAuthToken(String encryptedAccessToken, String encryptedRefreshToken, OffsetDateTime expiresAt) {
        this.encryptedSecret = encryptedAccessToken;
        if (encryptedRefreshToken != null) {
            this.encryptedRefreshToken = encryptedRefreshToken;
        }
        this.tokenExpiresAt = expiresAt;
        this.lastError = null;
    }

    public void recordAuthorizationFailure(String reason) {
        this.lastError = reason;
    }

    public boolean hasCredential() {
        return encryptedSecret != null && !encryptedSecret.isBlank();
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
        this.lastError = null;
    }

    /** The stored state claimed a live session that this process does not have. */
    public void markStale(String reason) {
        this.status = McpConnectionStatus.DISCONNECTED;
        this.lastError = reason;
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