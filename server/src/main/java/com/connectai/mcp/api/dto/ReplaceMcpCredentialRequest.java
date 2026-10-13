package com.connectai.mcp.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplaceMcpCredentialRequest(
        @NotBlank(message = "secret is required")
        @Size(max = 4000, message = "secret must not exceed 4000 characters")
        String secret
) {
}
