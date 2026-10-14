package com.connectai.mcp.service;

import com.connectai.mcp.api.dto.RegisterMcpServerRequest;
import com.connectai.mcp.api.dto.ReplaceMcpCredentialRequest;
import com.connectai.mcp.api.dto.ReplaceGrafanaCredentialsRequest;
import com.connectai.mcp.catalog.ConnectMethod;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.client.McpEndpoint;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.oauth.OAuthService;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpServer;
import com.connectai.mcp.operation.McpToolOperations;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpToolService implements McpToolOperations {

    private final McpServerRepository repository;
    private final SecretEncryptionService encryptionService;
    private final McpClientRegistry clientRegistry;
    private final OAuthService oauth;
    private final ObjectMapper mapper;
    @Value("${connectai.mcp.allow-private-hosts:false}")
    private boolean allowPrivateHosts;

    @Override
    @Transactional
    public McpServer registerTool(RegisterMcpServerRequest request) {
        String name = request.name().trim();
        String endpoint = request.endpoint().trim();

        validateRegistration(request, name, endpoint);

        log.info(
                "Registering MCP server: name={}, transport={}, authType={}, enabled={}",
                name,
                request.transport(),
                request.authType(),
                request.enabled() == null || request.enabled()
        );

        // OAuth connections get their token from the sign-in, never from the request.
        String encryptedSecret = request.authType() == McpAuthType.OAUTH
                || request.secret() == null || request.secret().isBlank()
                ? null
                : encryptionService.encrypt(request.secret());

        EMcpServer entity = new EMcpServer(
                name,
                request.transport(),
                endpoint,
                request.authType(),
                encryptedSecret,
                request.enabled() == null || request.enabled()
        );

        if (request.authType() == McpAuthType.OAUTH) {
            entity.useOAuthProvider(request.oauthProvider());
        }
        entity.setCatalogKey(request.catalogKey());
        entity.setConnectionAuth(request.authType() == McpAuthType.OAUTH ? ConnectMethod.OAUTH_APP
                : request.authType() == McpAuthType.BEARER ? ConnectMethod.TOKEN_BEARER : ConnectMethod.NONE,
                null);

        EMcpServer savedEntity = repository.save(entity);

        log.info(
                "MCP server registered: id={}, name={}, status={}",
                savedEntity.getId(),
                savedEntity.getName(),
                savedEntity.getStatus()
        );

        return McpServer.from(savedEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServer> getTools() {
        return repository.findAll()
                .stream()
                .map(entity -> McpServer.from(entity, clientRegistry.isConnected(entity.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public McpServer getTool(UUID id) {
        return repository.findById(id)
                .map(entity -> McpServer.from(entity, clientRegistry.isConnected(entity.getId())))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP server " + id + " not found"
                ));
    }

    @Override
    @Transactional
    public McpServer replaceBearerCredential(UUID id, ReplaceMcpCredentialRequest request) {
        EMcpServer entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MCP server " + id + " not found"));
        if (entity.getAuthType() != McpAuthType.BEARER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only manually configured bearer credentials can be replaced here; use the provider sign-in flow for OAuth.");
        }
        if (clientRegistry.isConnected(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Disconnect this MCP server before replacing its credential.");
        }
        entity.replaceBearerCredential(encryptionService.encrypt(request.secret()));
        EMcpServer saved = repository.save(entity);
        return McpServer.from(saved, false);
    }

    @Override
    @Transactional
    public McpServer replaceGrafanaCredentials(UUID id, ReplaceGrafanaCredentialsRequest request) {
        EMcpServer entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP connection not found"));
        if (!"grafana".equals(entity.getCatalogKey()) || entity.getConnectMethod() != ConnectMethod.TOKEN_HEADERS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This connection does not use Grafana credentials.");
        }
        if (clientRegistry.isConnected(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Disconnect Grafana before replacing its credentials.");
        }
        String grafanaUrl;
        try { grafanaUrl = com.connectai.mcp.client.GrafanaInstanceUrlPolicy.validate(request.grafanaUrl(), allowPrivateHosts); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("header:X-Grafana-URL", grafanaUrl);
        credentials.put("header:X-Grafana-Service-Account-Token", request.serviceAccountToken().trim());
        if (request.endpointToken() != null && !request.endpointToken().isBlank()) {
            credentials.put("header:Authorization", "Bearer " + request.endpointToken().trim());
        }
        try {
            entity.replaceAuthConfiguration(encryptionService.encrypt(mapper.writeValueAsString(credentials)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to encode Grafana authentication configuration");
        }
        return McpServer.from(repository.save(entity), false);
    }

    /** Rejects configurations that can never connect, before anything is stored. */
    private void validateRegistration(RegisterMcpServerRequest request, String name, String endpoint) {
        if (request.transport() != McpTransportType.STREAMABLE_HTTP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only the STREAMABLE_HTTP transport is supported; received " + request.transport());
        }
        try {
            McpEndpoint.parse(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (request.authType() == McpAuthType.OAUTH) {
            oauth.requireConfigured(request.oauthProvider());
        }
        if (request.authType() == McpAuthType.BEARER
                && (request.secret() == null || request.secret().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A token is required when authType is BEARER");
        }
        boolean duplicateName = repository.findAll().stream()
                .anyMatch(existing -> existing.getName().equalsIgnoreCase(name));
        if (duplicateName) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A connection named '" + name + "' already exists");
        }
    }
}
