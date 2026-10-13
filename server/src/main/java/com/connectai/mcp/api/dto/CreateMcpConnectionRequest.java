package com.connectai.mcp.api.dto;

import com.connectai.mcp.catalog.ConnectMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateMcpConnectionRequest(
        String catalogKey,
        @NotBlank @Size(max = 100) String name,
        String variant,
        @NotBlank @Size(max = 500) String endpoint,
        @NotNull ConnectMethod method,
        Map<String, String> credentials
) {
    public CreateMcpConnectionRequest {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
}
