package com.connectai.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.oauth.OAuthService;
import com.connectai.mcp.repository.McpServerRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class McpCatalogService {

    private static final String CATALOG_RESOURCE = "catalog/mcp-catalog.json";
    private final ObjectMapper mapper;
    private final McpServerRepository servers;
    private final com.connectai.mcp.client.McpClientRegistry clients;
    private final OAuthService oauth;
    private JsonNode catalog;

    @PostConstruct
    void loadAndValidate() {
        try (InputStream input = new ClassPathResource(CATALOG_RESOURCE).getInputStream()) {
            catalog = mapper.readTree(input);
            validate(catalog);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid MCP connector catalog resource " + CATALOG_RESOURCE
                    + ": " + exception.getMessage(), exception);
        }
    }

    public List<CatalogItemView> list() {
        List<CatalogItemView> result = new ArrayList<>();
        for (JsonNode sourceDefinition : catalog) {
            JsonNode definition = sourceDefinition.deepCopy();
            String key = definition.path("key").asText();
            List<EMcpServer> associated = servers.findAll().stream()
                    .filter(server -> belongsTo(server, key, definition.path("name").asText()))
                    .toList();
            boolean providerReady = true;
            String providerIssue = null;
            String providerRedirectUri = null;
            String providerId = definition.path("oauthProvider").asText("");
            if (!providerId.isBlank()) {
                OAuthService.ProviderInfo providerInfo = oauth.providers().stream()
                        .filter(provider -> provider.id().equals(providerId)).findFirst().orElse(null);
                providerReady = providerInfo != null && providerInfo.configured();
                providerRedirectUri = providerInfo == null ? null : providerInfo.redirectUri();
                // A catalog provider missing from OAuth configuration follows the declared
                // requiredServerSettings guidance below. A configured-but-invalid provider has
                // a precise validation reason from OAuthService.
                providerIssue = providerInfo == null ? null : providerInfo.configurationIssue();
            }
            for (JsonNode method : definition.path("methods")) {
                if ("OAUTH_APP".equals(method.path("id").asText()) && !providerReady) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) method).put("supported", false);
                }
            }
            String status = deriveStatus(definition, associated);
            String statusReason = deriveStatusReason(definition, associated, providerReady, providerIssue, providerRedirectUri);
            boolean adminSetupRequired = !providerReady && definition.hasNonNull("requiredServerSettings")
                    && !hasSupportedNonOAuthMethod(definition);
            result.add(new CatalogItemView(definition, associated.size(), status,
                    associated.stream().map(server -> com.connectai.mcp.model.McpServer.from(
                            server, clients.isConnected(server.getId()))).toList(), statusReason, adminSetupRequired));
        }
        return List.copyOf(result);
    }

    public JsonNode definition(String key) {
        for (JsonNode item : catalog) if (item.path("key").asText().equals(key)) return item.deepCopy();
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Unknown catalog connector");
    }

    private String deriveStatus(JsonNode definition, List<EMcpServer> associated) {
        if (associated.stream().anyMatch(server -> clients.isConnected(server.getId()))) return "CONNECTED";
        if (associated.stream().anyMatch(server -> server.getStatus() == McpConnectionStatus.REAUTH_REQUIRED
                || server.getAuthType() == com.connectai.mcp.model.McpAuthType.OAUTH && !server.hasCredential())) {
            return "NEEDS_SIGN_IN";
        }
        if (associated.stream().anyMatch(server -> server.getStatus() == McpConnectionStatus.FAILED)) return "CONNECTION_ERROR";
        boolean supported = false;
        for (JsonNode method : definition.path("methods")) {
            if (method.path("supported").asBoolean(false)) supported = true;
        }
        if (!supported) return "SETUP_REQUIRED";
        return "AVAILABLE";
    }

    private String deriveStatusReason(JsonNode definition, List<EMcpServer> associated, boolean providerReady,
                                      String providerIssue, String providerRedirectUri) {
        if (associated.stream().anyMatch(server -> clients.isConnected(server.getId()))) {
            return "A live MCP session is connected and tools were discovered.";
        }
        if (associated.stream().anyMatch(server -> server.getStatus() == McpConnectionStatus.REAUTH_REQUIRED)) {
            return "The saved authorization has expired or was rejected. Re-authorize this connection.";
        }
        if (associated.stream().anyMatch(server -> server.getStatus() == McpConnectionStatus.FAILED)) {
            return associated.stream().filter(server -> server.getStatus() == McpConnectionStatus.FAILED)
                    .map(EMcpServer::getLastError).filter(java.util.Objects::nonNull).findFirst()
                    .orElse("The last MCP connection attempt failed. Test the endpoint and credentials.");
        }
        boolean anySupported = false;
        for (JsonNode method : definition.path("methods")) anySupported |= method.path("supported").asBoolean(false);
        if (!providerReady && definition.hasNonNull("requiredServerSettings")) {
            List<String> settings = new ArrayList<>();
            for (JsonNode setting : definition.path("requiredServerSettings")) settings.add(setting.asText());
            // Provider-specific callback/redirect failures need their specialized guidance.
            // Missing OAuth client settings are handled below so a supported PAT/header
            // method remains available and is reported separately from OAuth readiness.
            if (providerIssue != null
                    && !providerIssue.startsWith("OAuth sign-in is missing server settings:")) {
                String action = settings.contains("SLACK_OAUTH_REDIRECT_URI")
                        ? " Set SLACK_OAUTH_REDIRECT_URI to an HTTPS URL ending in /connect-ai/api/oauth/callback and register that exact URL in the Slack app."
                        : " A server administrator must correct the OAuth configuration before provider sign-in can be used.";
                return providerIssue + action;
            }
            String callbackGuidance;
            if (providerRedirectUri != null && !providerRedirectUri.isBlank()) {
                callbackGuidance = " Register this callback URI with the provider: " + providerRedirectUri + ".";
            } else if (settings.contains("SLACK_OAUTH_REDIRECT_URI")) {
                callbackGuidance = " Set SLACK_OAUTH_REDIRECT_URI to the deployment's HTTPS /connect-ai/api/oauth/callback URL and register that same URL in Slack; Slack does not accept the local HTTP callback.";
            } else {
                callbackGuidance = " Register the backend's configured callback URI with the provider: " + oauth.redirectUri() + ".";
            }
            String setup = "A server administrator must configure " + String.join(", ", settings)
                    + " before provider sign-in can be used." + callbackGuidance;
            if (!anySupported) return setup;
            String supported = "Ready to connect using " + firstSupportedLabel(definition) + ". ";
            return supported + "To enable provider sign-in, " + setup.substring(0, 1).toLowerCase()
                    + setup.substring(1);
        }
        if (!anySupported && definition.hasNonNull("unavailableReason")) {
            return definition.path("unavailableReason").asText();
        }
        if (!providerReady && definition.hasNonNull("unavailableReason")) {
            return definition.path("unavailableReason").asText();
        }
        return "Ready to connect using " + firstSupportedLabel(definition) + ".";
    }

    private static String firstSupportedLabel(JsonNode definition) {
        String firstSupportedLabel = "the supported connection method";
        for (JsonNode method : definition.path("methods")) {
            if (method.path("supported").asBoolean(false)) {
                firstSupportedLabel = method.path("label").asText(firstSupportedLabel);
                break;
            }
        }
        return firstSupportedLabel;
    }

    private static boolean hasSupportedNonOAuthMethod(JsonNode definition) {
        for (JsonNode method : definition.path("methods")) {
            if (method.path("supported").asBoolean(false)
                    && !"OAUTH_APP".equals(method.path("id").asText())) return true;
        }
        return false;
    }

    private static boolean belongsTo(EMcpServer server, String key, String displayName) {
        if (key.equals(server.getCatalogKey())) return true;
        // Compatibility for rows created before catalog_key was introduced.
        String name = server.getName().toLowerCase(Locale.ROOT);
        String expected = displayName.toLowerCase(Locale.ROOT);
        return name.equals(expected) || name.startsWith(expected + " - ");
    }

    private static void validate(JsonNode root) {
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("catalog root must be a non-empty array");
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode item : root) {
            for (String field : List.of("key", "name", "description", "category", "iconKey", "methods",
                    "defaultEndpoint", "endpointEditable", "variants", "fields", "permissionsText", "help", "scopes")) {
                if (!item.has(field)) throw new IllegalArgumentException("entry is missing required field '" + field + "'");
            }
            String key = item.path("key").asText("");
            if (key.isBlank() || !keys.add(key)) throw new IllegalArgumentException("catalog keys must be non-empty and unique");
            if (!item.path("methods").isArray() || item.path("methods").isEmpty()) {
                throw new IllegalArgumentException("entry '" + key + "' must have at least one method");
            }
            Set<String> methodIds = new HashSet<>();
            for (JsonNode method : item.path("methods")) {
                String id = method.path("id").asText("");
                try {
                    ConnectMethod.valueOf(id);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("entry '" + key + "' has unknown method '" + id + "'");
                }
                if (!methodIds.add(id) || !method.has("supported") || !method.hasNonNull("label")) {
                    throw new IllegalArgumentException("entry '" + key + "' has an invalid or duplicate method '" + id + "'");
                }
            }
            if (!item.path("help").has("steps") || !item.path("help").path("steps").isArray()) {
                throw new IllegalArgumentException("entry '" + key + "' help.steps must be an array");
            }
        }
    }

    public record CatalogItemView(JsonNode connector, int connectionCount, String status,
                                  List<com.connectai.mcp.model.McpServer> connections,
                                  String statusReason, boolean adminSetupRequired) { }
}
