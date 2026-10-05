package com.connectai.mcp.entity;

import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.model.McpTransportType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "mcp_server")
@Getter
@NoArgsConstructor
public class EMcpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private McpTransportType transport;

    @Column(nullable = false, length = 500)
    private String endpoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpAuthType authType;

    @Column(length = 4096)
    private String encryptedSecret;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private McpConnectionStatus status;

    @Column(length = 1000)
    private String lastError;

    private OffsetDateTime lastConnectedAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public EMcpServer(
            String name,
            McpTransportType transport,
            String endpoint,
            McpAuthType authType,
            String encryptedSecret,
            boolean enabled) {
        this.name = name;
        this.transport = transport;
        this.endpoint = endpoint;
        this.authType = authType;
        this.encryptedSecret = encryptedSecret;
        this.enabled = enabled;
        this.status = McpConnectionStatus.REGISTERED;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
