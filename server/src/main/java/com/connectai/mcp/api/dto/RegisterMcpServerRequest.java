package com.connectai.mcp.api.dto;

import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterMcpServerRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must not exceed 100 characters")
        String name,

        @NotNull(message = "transport is required")
        McpTransportType transport,

        @NotBlank(message = "endpoint is required")
        @Size(max = 500, message = "endpoint must not exceed 500 characters")
        String endpoint,

        @NotNull(message = "authType is required")
        McpAuthType authType,

        String secret,

        Boolean enabled,

        /** For authType OAUTH: the provider to sign in with (a connectai.oauth.providers key, e.g. "github"). */
        String oauthProvider,

        @Size(max = 100, message = "catalogKey must not exceed 100 characters")
        String catalogKey
) {
    public RegisterMcpServerRequest(String name, McpTransportType transport, String endpoint, McpAuthType authType,
                                    String secret, Boolean enabled) {
        this(name, transport, endpoint, authType, secret, enabled, null, null);
    }

    public RegisterMcpServerRequest(String name, McpTransportType transport, String endpoint, McpAuthType authType,
                                    String secret, Boolean enabled, String oauthProvider) {
        this(name, transport, endpoint, authType, secret, enabled, oauthProvider, null);
    }
}
