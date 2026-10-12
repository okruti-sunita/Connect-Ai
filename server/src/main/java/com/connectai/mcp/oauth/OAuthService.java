package com.connectai.mcp.oauth;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OAuth 2.0 Authorization Code flow (with PKCE) that gives an MCP connection a token without the
 * user pasting one.
 *
 * <ol>
 *     <li>{@link #start}: creates a single-use, expiring {@code state} bound to one connection and a
 *         PKCE verifier, and returns the provider's authorization URL (opened in a popup).</li>
 *     <li>{@link #complete}: the provider redirects the browser to the callback; the state is consumed,
 *         the code is exchanged server-side and the token is stored encrypted on the connection.</li>
 *     <li>{@link #attempt}: the UI polls the outcome (the popup's link to the app cannot be relied
 *         on after it visited the provider's site).</li>
 *     <li>{@link #accessToken}: returns a usable token, refreshing it first when it expires and the
 *         provider issued a refresh token.</li>
 * </ol>
 *
 * Tokens, codes and secrets are never logged or returned by the API.
 */
@Service
@Slf4j
public class OAuthService {

    public enum AttemptStatus { PENDING, SUCCEEDED, FAILED }

    public record Attempt(String id, UUID serverId, String providerId, AttemptStatus status, String message) { }

    public record ProviderInfo(String id, String displayName, boolean configured) { }

    public record Started(String attemptId, String authorizationUrl) { }

    private record Pending(UUID serverId, String providerId, String verifier, Instant createdAt) { }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    private final OAuthProperties properties;
    private final McpServerRepository repository;
    private final SecretEncryptionService encryption;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Keyed by state. Single-use; discarded after the TTL. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Keyed by state, kept for the TTL so the UI can read the outcome. */
    private final Map<String, Attempt> outcomes = new ConcurrentHashMap<>();

    public OAuthService(
            OAuthProperties properties,
            McpServerRepository repository,
            SecretEncryptionService encryption,
            ObjectMapper mapper) {
        this.properties = properties;
        this.repository = repository;
        this.encryption = encryption;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public List<ProviderInfo> providers() {
        return properties.getProviders().entrySet().stream()
                .map(e -> new ProviderInfo(e.getKey(),
                        e.getValue().getDisplayName() == null ? e.getKey() : e.getValue().getDisplayName(),
                        e.getValue().isConfigured()))
                .toList();
    }

    /** Validates a provider id for a new OAUTH connection. */
    public void requireConfigured(String providerId) {
        OAuthProperties.Provider provider = providerId == null ? null : properties.getProviders().get(providerId);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown OAuth provider '" + providerId + "'");
        }
        if (!provider.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sign-in with " + displayName(providerId, provider)
                    + " is not configured on this server. Set its OAuth client id and secret (see README), or use an access token.");
        }
    }

    public Started start(UUID serverId) {
        purgeExpired();
        EMcpServer server = repository.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server " + serverId + " not found"));
        if (server.getAuthType() != McpAuthType.OAUTH || server.getOauthProvider() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + server.getName() + "' does not use OAuth sign-in");
        }
        String providerId = server.getOauthProvider();
        requireConfigured(providerId);
        OAuthProperties.Provider provider = properties.getProviders().get(providerId);

        String state = randomToken();
        String verifier = randomToken() + randomToken();
        pending.put(state, new Pending(serverId, providerId, verifier, Instant.now()));
        outcomes.put(state, new Attempt(state, serverId, providerId, AttemptStatus.PENDING, "Waiting for sign-in"));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.getClientId());
        params.put("redirect_uri", properties.getRedirectUri());
        if (!provider.getScopes().isBlank()) {
            params.put("scope", provider.getScopes());
        }
        params.put("state", state);
        if (provider.isPkce()) {
            params.put("code_challenge", challenge(verifier));
            params.put("code_challenge_method", "S256");
        }
        log.info("OAuth sign-in started: serverId={}, provider={}", serverId, providerId);
        return new Started(state, provider.getAuthorizeUrl() + "?" + form(params));
    }

    /** Handles the provider's redirect. Never throws for user-caused failures; they become the outcome. */
    public Attempt complete(String state, String code, String error, String errorDescription) {
        purgeExpired();
        Pending started = state == null ? null : pending.remove(state);
        if (started == null) {
            return new Attempt(state, null, null, AttemptStatus.FAILED,
                    "This sign-in link is invalid or has expired. Start the sign-in again.");
        }

        EMcpServer server = repository.findById(started.serverId()).orElse(null);
        if (server == null) {
            return finish(state, started, AttemptStatus.FAILED, "The connection was removed during sign-in.", null);
        }
        if (error != null && !error.isBlank()) {
            String reason = "access_denied".equals(error)
                    ? "Sign-in was cancelled or access was denied."
                    : "The provider reported an error: " + error + (errorDescription == null ? "" : " (" + errorDescription + ")");
            return finish(state, started, AttemptStatus.FAILED, reason, server);
        }
        if (code == null || code.isBlank()) {
            return finish(state, started, AttemptStatus.FAILED, "The provider did not return an authorization code.", server);
        }

        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("grant_type", "authorization_code");
            body.put("code", code);
            body.put("redirect_uri", properties.getRedirectUri());
            OAuthProperties.Provider provider = properties.getProviders().get(started.providerId());
            if (provider.isPkce()) {
                body.put("code_verifier", started.verifier());
            }
            storeTokens(server, requestToken(provider, body));
            log.info("OAuth sign-in completed: serverId={}, provider={}", server.getId(), started.providerId());
            return finish(state, started, AttemptStatus.SUCCEEDED, "Signed in. You can close this window.", null);
        } catch (OAuthException exception) {
            log.warn("OAuth token exchange failed: serverId={}, provider={}, cause={}",
                    server.getId(), started.providerId(), exception.getMessage());
            return finish(state, started, AttemptStatus.FAILED, exception.getMessage(), server);
        }
    }

    public Attempt attempt(String attemptId) {
        purgeExpired();
        Attempt attempt = attemptId == null ? null : outcomes.get(attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired sign-in attempt");
        }
        return attempt;
    }

    /**
     * The token to send for an OAUTH connection, refreshed first when it is about to expire and a
     * refresh token exists. Throws when the connection has no usable token (sign in again).
     */
    public String accessToken(EMcpServer server) {
        if (!server.hasCredential()) {
            throw new IllegalStateException("Not signed in yet. Use 'Sign in' on this connection first.");
        }
        OffsetDateTime expiresAt = server.getTokenExpiresAt();
        boolean expiring = expiresAt != null && expiresAt.isBefore(OffsetDateTime.now().plus(REFRESH_MARGIN));
        if (expiring) {
            if (server.getEncryptedRefreshToken() == null) {
                throw new IllegalStateException("The sign-in has expired. Sign in again.");
            }
            OAuthProperties.Provider provider = properties.getProviders().get(server.getOauthProvider());
            if (provider == null || !provider.isConfigured()) {
                throw new IllegalStateException("The OAuth provider of this connection is no longer configured.");
            }
            Map<String, String> body = new LinkedHashMap<>();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", encryption.decrypt(server.getEncryptedRefreshToken()));
            try {
                storeTokens(server, requestToken(provider, body));
                log.info("OAuth token refreshed: serverId={}", server.getId());
            } catch (OAuthException exception) {
                throw new IllegalStateException("Refreshing the sign-in failed: " + exception.getMessage() + " Sign in again.");
            }
        }
        return encryption.decrypt(server.getEncryptedSecret());
    }

    // ---------------------------------------------------------------- internals

    private void storeTokens(EMcpServer server, JsonNode token) {
        String access = token.path("access_token").asText("");
        if (access.isBlank()) {
            throw new OAuthException("The provider returned no access token.");
        }
        String refresh = token.path("refresh_token").asText("");
        long expiresIn = token.path("expires_in").asLong(0);
        server.storeOAuthToken(
                encryption.encrypt(access),
                refresh.isBlank() ? null : encryption.encrypt(refresh),
                expiresIn > 0 ? OffsetDateTime.now().plusSeconds(expiresIn) : null);
        repository.save(server);
    }

    private JsonNode requestToken(OAuthProperties.Provider provider, Map<String, String> body) {
        body.put("client_id", provider.getClientId());
        body.put("client_secret", provider.getClientSecret());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(provider.getTokenUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
            // GitHub answers errors with HTTP 200 and an "error" field.
            if (json.hasNonNull("error")) {
                throw new OAuthException("The provider rejected the sign-in: " + json.path("error").asText()
                        + (json.hasNonNull("error_description") ? " (" + json.path("error_description").asText() + ")" : ""));
            }
            if (response.statusCode() / 100 != 2) {
                throw new OAuthException("The provider's token endpoint returned HTTP " + response.statusCode() + ".");
            }
            return json;
        } catch (OAuthException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OAuthException("Interrupted while contacting the provider.");
        } catch (Exception exception) {
            throw new OAuthException("Could not reach the provider's token endpoint ("
                    + exception.getClass().getSimpleName() + ").");
        }
    }

    private Attempt finish(String state, Pending started, AttemptStatus status, String message, EMcpServer failedServer) {
        if (failedServer != null && status == AttemptStatus.FAILED) {
            failedServer.recordAuthorizationFailure(message);
            repository.save(failedServer);
        }
        Attempt attempt = new Attempt(state, started.serverId(), started.providerId(), status, message);
        outcomes.put(state, attempt);
        return attempt;
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minusSeconds(properties.getAttemptTtlSeconds());
        pending.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
        outcomes.keySet().removeIf(state -> !pending.containsKey(state)
                && outcomes.get(state) != null && outcomes.get(state).status() == AttemptStatus.PENDING);
        if (outcomes.size() > 1000) {
            outcomes.clear(); // bounded memory; only abandoned attempts accumulate
        }
    }

    private static String displayName(String id, OAuthProperties.Provider provider) {
        return provider.getDisplayName() == null ? id : provider.getDisplayName();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static final class OAuthException extends RuntimeException {
        OAuthException(String message) {
            super(message);
        }
    }
}
