package com.connectai.mcp.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplaceGrafanaCredentialsRequest(
        @NotBlank @Size(max = 500) String grafanaUrl,
        @NotBlank @Size(max = 4000) String serviceAccountToken,
        @Size(max = 4000) String endpointToken
) { }
