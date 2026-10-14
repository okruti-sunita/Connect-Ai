package com.connectai.mcp.service;

import com.connectai.mcp.api.dto.ReplaceGrafanaCredentialsRequest;
import com.connectai.mcp.catalog.ConnectMethod;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.oauth.OAuthService;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolServiceGrafanaCredentialTest {
    @Test
    void rotationReplacesEncryptedGrafanaHeadersAndDoesNotEchoToken() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SecretEncryptionService encryption = new SecretEncryptionService("grafana-rotation-test-key-long-enough");
        EMcpServer entity = new EMcpServer("Grafana - Main", McpTransportType.STREAMABLE_HTTP,
                "https://mcp.example.test/mcp", McpAuthType.BEARER, null, true);
        entity.setCatalogKey("grafana");
        entity.setConnectionAuth(ConnectMethod.TOKEN_HEADERS, encryption.encrypt(mapper.writeValueAsString(Map.of(
                "header:X-Grafana-URL", "https://8.8.8.8",
                "header:X-Grafana-Service-Account-Token", "old-token"))));
        UUID id = UUID.randomUUID();
        McpServerRepository repository = mock(McpServerRepository.class);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any(EMcpServer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        McpClientRegistry registry = mock(McpClientRegistry.class);
        McpToolService service = new McpToolService(repository, encryption, registry, mock(OAuthService.class), mapper);

        var response = service.replaceGrafanaCredentials(id,
                new ReplaceGrafanaCredentialsRequest("https://8.8.8.8/grafana", "new-sensitive-token", null));

        Map<String, String> stored = mapper.readValue(encryption.decrypt(entity.getEncryptedAuthConfig()), new TypeReference<>() { });
        assertEquals(Map.of("header:X-Grafana-URL", "https://8.8.8.8/grafana",
                "header:X-Grafana-Service-Account-Token", "new-sensitive-token"), stored);
        assertEquals(ConnectMethod.TOKEN_HEADERS, response.connectMethod());
        org.junit.jupiter.api.Assertions.assertFalse(entity.getEncryptedAuthConfig().contains("new-sensitive-token"));
        verify(registry).isConnected(id);
    }
}
