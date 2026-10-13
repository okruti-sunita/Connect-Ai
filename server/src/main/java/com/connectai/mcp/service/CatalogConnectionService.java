package com.connectai.mcp.service;

import com.connectai.mcp.api.dto.CreateMcpConnectionRequest;
import com.connectai.mcp.api.dto.CreatedMcpConnectionResponse;
import com.connectai.mcp.api.dto.McpConnectionResponse;
import com.connectai.mcp.api.dto.McpConnectionTestResponse;
import com.connectai.mcp.catalog.ConnectMethod;
import com.connectai.mcp.catalog.McpCatalogService;
import com.connectai.mcp.client.McpClientFactory;
import com.connectai.mcp.client.McpConnectionService;
import com.connectai.mcp.client.McpEndpoint;
import com.connectai.mcp.client.McpToolDiscoveryService;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpServer;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CatalogConnectionService {
    private static final int MAX_PAGES = 100;

    private final McpCatalogService catalog;
    private final McpClientFactory clientFactory;
    private final McpConnectionService connections;
    private final McpToolDiscoveryService discovery;
    private final McpServerRepository servers;
    private final SecretEncryptionService encryption;
    private final ObjectMapper mapper;

    public McpConnectionTestResponse test(CreateMcpConnectionRequest request) {
        EMcpServer candidate = candidate(request);
        McpSyncClient client = null;
        try {
            client = clientFactory.create(candidate);
            McpSchema.InitializeResult initialized = client.initialize();
            if (initialized == null) throw new IllegalStateException("MCP server returned no initialize result");
            List<String> toolNames = listToolNames(client);
            return new McpConnectionTestResponse(true, List.of(
                    new McpConnectionTestResponse.Step("endpoint", true, "ENDPOINT_REACHABLE", "Endpoint responded", null),
                    new McpConnectionTestResponse.Step("initialize", true, "MCP_INITIALIZED", "MCP handshake completed", null),
                    new McpConnectionTestResponse.Step("discovery", true, "TOOLS_DISCOVERED", "Tool discovery completed", null)),
                    toolNames.size(), toolNames, null, null, null);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            return new McpConnectionTestResponse(false, List.of(
                    new McpConnectionTestResponse.Step("connection", false, "CONNECTION_FAILED", message,
                            "Check the endpoint, authentication method, and server availability.")),
                    0, List.of(), "CONNECTION_FAILED", message,
                    "Verify the endpoint and credentials, then retry.");
        } finally {
            if (client != null) {
                try { client.closeGracefully(); } catch (RuntimeException ignored) { }
            }
        }
    }

    public CreatedMcpConnectionResponse create(CreateMcpConnectionRequest request) {
        EMcpServer entity = candidate(request);
        entity = servers.save(entity);
        try {
            McpConnectionResponse connected = connections.connect(entity.getId());
            if (!connected.initialized()) throw new IllegalStateException("MCP initialization did not complete");
            var tools = discovery.discover(entity.getId()).tools();
            List<String> names = tools.stream().map(tool -> tool.name()).toList();
            return new CreatedMcpConnectionResponse(McpServer.from(servers.findById(entity.getId()).orElse(entity), true),
                    names.size(), names);
        } catch (RuntimeException failure) {
            try {
                connections.disconnect(entity.getId());
                connections.remove(entity.getId());
            } catch (RuntimeException cleanupFailure) {
                servers.deleteById(entity.getId());
            }
            throw failure;
        }
    }

    private EMcpServer candidate(CreateMcpConnectionRequest request) {
        if (request.method() == ConnectMethod.OAUTH_APP || request.method() == ConnectMethod.OAUTH_DISCOVERY) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "OAuth connections must be started through the configured provider authorization flow.");
        }
        if (request.catalogKey() != null && !request.catalogKey().isBlank()) {
            var definition = catalog.definition(request.catalogKey());
            boolean supported = false;
            for (var method : definition.path("methods")) {
                if (request.method().name().equals(method.path("id").asText())
                        && method.path("supported").asBoolean(false)) supported = true;
            }
            if (!supported) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The selected connection method is not supported for this connector.");
        }
        try { McpEndpoint.parse(request.endpoint()); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The MCP endpoint is invalid.");
        }
        Map<String, String> credentials = request.credentials();
        validateCredentials(request.method(), credentials);
        boolean duplicate = servers.findAll().stream().anyMatch(server ->
                server.getName().equalsIgnoreCase(request.name().trim()));
        if (duplicate) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A connection with this name already exists. Choose a different name.");
        String json;
        try { json = mapper.writeValueAsString(credentials); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to encode authentication configuration"); }
        McpAuthType legacyAuth = request.method() == ConnectMethod.NONE ? McpAuthType.NONE
                : request.method() == ConnectMethod.OAUTH_APP || request.method() == ConnectMethod.OAUTH_DISCOVERY
                ? McpAuthType.OAUTH : McpAuthType.BEARER;
        EMcpServer entity = new EMcpServer(request.name().trim(), McpTransportType.STREAMABLE_HTTP,
                request.endpoint().trim(), legacyAuth, null, true);
        entity.setCatalogKey(request.catalogKey());
        entity.setConnectionAuth(request.method(), request.method() == ConnectMethod.NONE ? null : encryption.encrypt(json));
        return entity;
    }

    private static void validateCredentials(ConnectMethod method, Map<String, String> credentials) {
        switch (method) {
            case NONE -> { if (!credentials.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NONE authentication does not accept credentials."); }
            case TOKEN_BEARER -> require(credentials, "token");
            case TOKEN_BASIC -> { require(credentials, "username"); require(credentials, "token"); }
            case TOKEN_HEADER -> { require(credentials, "headerName"); require(credentials, "token"); }
            case TOKEN_HEADERS -> {
                if (credentials.isEmpty() || credentials.keySet().stream().anyMatch(key -> !key.startsWith("header:")))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide one or more named authentication headers.");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported authentication method.");
        }
    }

    private static void require(Map<String, String> credentials, String name) {
        if (credentials.get(name) == null || credentials.get(name).isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A required credential field is missing: " + name);
    }

    private static List<String> listToolNames(McpSyncClient client) {
        List<String> result = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        McpSchema.ListToolsResult page = client.listTools();
        int pages = 0;
        while (page != null) {
            if (++pages > MAX_PAGES) throw new IllegalStateException("MCP tool discovery exceeded the page limit");
            if (page.tools() != null) page.tools().forEach(tool -> result.add(tool.name()));
            String cursor = page.nextCursor();
            if (cursor == null || cursor.isBlank()) return List.copyOf(result);
            if (!cursors.add(cursor)) throw new IllegalStateException("MCP tool discovery returned a repeated pagination cursor");
            page = client.listTools(cursor);
        }
        return List.copyOf(result);
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof java.net.http.HttpConnectTimeoutException
                || exception instanceof java.net.http.HttpTimeoutException
                || exception instanceof java.util.concurrent.TimeoutException) {
            return "The MCP server did not respond before the connection timed out.";
        }
        if (exception instanceof java.net.UnknownHostException) {
            return "The MCP endpoint host could not be resolved.";
        }
        return "MCP connection, initialization, or tool discovery failed. Check the endpoint and authentication, then retry.";
    }
}
