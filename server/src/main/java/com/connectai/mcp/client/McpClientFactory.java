package com.connectai.mcp.client;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.catalog.ConnectMethod;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.oauth.OAuthService;
import com.connectai.mcp.security.SecretEncryptionService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class McpClientFactory {

    private final SecretEncryptionService encryptionService;
    private final OAuthService oauth;
    private final Duration requestTimeout;
    private final Duration connectTimeout;
    private final ObjectMapper mapper;
    private final boolean allowPrivateHosts;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    @Autowired
    public McpClientFactory(
            SecretEncryptionService encryptionService,
            ObjectProvider<OAuthService> oauth,
            ObjectMapper mapper,
            @Value("${connectai.mcp.allow-private-hosts:false}") boolean allowPrivateHosts,
            @Value("${connectai.mcp.request-timeout-seconds:20}") long requestTimeoutSeconds,
            @Value("${connectai.mcp.connect-timeout-seconds:10}") long connectTimeoutSeconds) {
        this.encryptionService = encryptionService;
        this.oauth = oauth.getIfAvailable();
        this.mapper = mapper;
        this.allowPrivateHosts = allowPrivateHosts;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
    }

    /** Without OAuth support (tests and token-only setups). */
    public McpClientFactory(
            SecretEncryptionService encryptionService,
            long requestTimeoutSeconds,
            long connectTimeoutSeconds) {
        this.encryptionService = encryptionService;
        this.oauth = null;
        this.mapper = new ObjectMapper();
        this.allowPrivateHosts = true;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
    }

    public McpSyncClient create(EMcpServer server) {
        if (server.getTransport() == null) {
            throw new IllegalArgumentException("MCP transport is required");
        }
        if (server.getTransport() != McpTransportType.STREAMABLE_HTTP) {
            throw new UnsupportedOperationException(
                    "Task 2 supports STREAMABLE_HTTP only; received " + server.getTransport());
        }

        McpEndpoint endpoint = McpEndpointPolicy.validate(server.getEndpoint(), allowPrivateHosts);
        HttpClientStreamableHttpTransport.Builder transportBuilder =
                HttpClientStreamableHttpTransport
                        .builder(endpoint.baseUrl())
                        .endpoint(endpoint.path())
                        .connectTimeout(connectTimeout)
                        // Never follow a server-controlled redirect from a user-supplied MCP
                        // endpoint. It could redirect an otherwise public URL to a private host.
                        .customizeClient(clientBuilder -> clientBuilder.followRedirects(HttpClient.Redirect.NEVER));

        ConnectMethod method = server.getConnectMethod();
        if (method == null) {
            method = server.getAuthType() == McpAuthType.OAUTH ? ConnectMethod.OAUTH_APP
                    : server.getAuthType() == McpAuthType.BEARER ? ConnectMethod.TOKEN_BEARER : ConnectMethod.NONE;
        }
        if (method == ConnectMethod.OAUTH_APP) {
                if (oauth == null) {
                    throw new IllegalStateException("OAuth sign-in is not available in this configuration");
                }
                // A connection may outlive an access token. Resolve it for each HTTP request so
                // accessToken can refresh before expiry and the request uses the refreshed value.
                transportBuilder.httpRequestCustomizer(
                        (requestBuilder, requestMethod, uri, body, context) ->
                                requestBuilder.header("Authorization", "Bearer " + oauth.accessToken(server)));
        } else if (method == ConnectMethod.OAUTH_DISCOVERY) {
            throw new UnsupportedOperationException("OAuth protected-resource discovery is not implemented yet");
        } else {
            Map<String, String> headers = tokenHeaders(server, method);
            if (!headers.isEmpty()) {
                transportBuilder.httpRequestCustomizer((requestBuilder, requestMethod, uri, body, context) ->
                        headers.forEach(requestBuilder::header));
            }
        }

        return McpClient.sync(transportBuilder.build())
                .clientInfo(new McpSchema.Implementation("Connect AI", "0.1.0"))
                .requestTimeout(requestTimeout)
                .build();
    }

    private Map<String, String> tokenHeaders(EMcpServer server, ConnectMethod method) {
        if (method == ConnectMethod.NONE) return Map.of();
        Map<String, String> credentials;
        if (server.getEncryptedAuthConfig() != null && !server.getEncryptedAuthConfig().isBlank()) {
            try {
                String json = encryptionService.decrypt(server.getEncryptedAuthConfig());
                credentials = mapper.readValue(json, new TypeReference<>() { });
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to read encrypted MCP authentication configuration");
            }
        } else {
            String legacy = encryptionService.decrypt(server.getEncryptedSecret());
            credentials = legacy == null ? Map.of() : Map.of("token", legacy);
        }
        Map<String, String> result = new LinkedHashMap<>();
        switch (method) {
            case TOKEN_BEARER -> result.put("Authorization", "Bearer " + require(credentials, "token"));
            case TOKEN_BASIC -> {
                String pair = require(credentials, "username") + ":" + require(credentials, "token");
                result.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
            }
            case TOKEN_HEADER -> result.put(validHeaderName(require(credentials, "headerName")), require(credentials, "token"));
            case TOKEN_HEADERS -> credentials.forEach((key, value) -> {
                if (!key.startsWith("header:")) throw new IllegalStateException("Invalid configured MCP header name");
                result.put(validHeaderName(key.substring("header:".length())), validateHeaderValue(value));
            });
            case NONE -> { }
            default -> throw new UnsupportedOperationException("Unsupported MCP connection method: " + method);
        }
        result.replaceAll((name, value) -> validateHeaderValue(value));
        return Map.copyOf(result);
    }

    private static String require(Map<String, String> credentials, String key) {
        String value = credentials.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required MCP credential field: " + key);
        return value;
    }

    private static String validHeaderName(String name) {
        if (!HEADER_NAME.matcher(name).matches() || SetOfForbiddenHeaders.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid or prohibited MCP authentication header name");
        }
        return name;
    }

    private static String validateHeaderValue(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid MCP authentication header value");
        }
        return value;
    }

    private static final java.util.Set<String> SetOfForbiddenHeaders = java.util.Set.of(
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "proxy-authorization");
}
