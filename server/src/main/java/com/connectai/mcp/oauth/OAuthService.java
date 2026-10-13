package com.connectai.mcp.oauth;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.agent.SafeDiagnostic;
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
import java.io.InputStream;
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

    public enum AttemptStatus { PENDING, CONNECTING, SUCCEEDED, FAILED }

    public record Attempt(String id, UUID serverId, String providerId, AttemptStatus status, String message) { }

    public record ProviderInfo(String id, String displayName, boolean configured, List<String> scopes,
                               String redirectUri) { }

    public record Started(String attemptId, String authorizationUrl, String cookieName, String cookieValue) { }

    private record Pending(UUID serverId, String providerId, String verifier, String attemptId,
                           String browserBindingHash, Instant createdAt) { }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final OAuthProperties properties;
    private final McpServerRepository repository;
    private final SecretEncryptionService encryption;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Keyed by state. Single-use; discarded after the TTL. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Keyed by an opaque polling id, separate from OAuth state. */
    private final Map<String, Attempt> outcomes = new ConcurrentHashMap<>();
    private final Map<String, Instant> outcomeCreatedAt = new ConcurrentHashMap<>();

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
                        e.getValue().isConfigured(), scopes(e.getValue()), properties.getRedirectUri()))
                .toList();
    }

    /** Safe callback URI for setup guidance; contains no credentials. */
    public String redirectUri() {
        return properties.getRedirectUri();
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
        validateRedirectUri(properties.getRedirectUri());
        validateProvider(providerId, provider);
    }

    public Started start(UUID serverId) {
        purgeExpired();
        if (pending.size() >= 1000) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many OAuth sign-ins are in progress. Wait for an attempt to finish or expire, then retry.");
        }
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
        String attemptId = randomToken();
        String cookieName = "connectai_oauth_" + state;
        String cookieValue = randomToken();
        String verifier = randomToken() + randomToken();
        pending.put(state, new Pending(serverId, providerId, verifier, attemptId, sha256(cookieValue), Instant.now()));
        outcomes.put(attemptId, new Attempt(attemptId, serverId, providerId, AttemptStatus.PENDING, "Waiting for sign-in"));
        outcomeCreatedAt.put(attemptId, Instant.now());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.getClientId());
        params.put("redirect_uri", properties.getRedirectUri());
        if (!provider.getScopes().isBlank()) {
            params.put("scope", provider.getScopes());
        }
        if (provider.getAccessType() != null && !provider.getAccessType().isBlank()) {
            params.put("access_type", provider.getAccessType());
        }
        params.putAll(provider.getAuthorizationParameters());
        params.put("state", state);
        if (provider.isPkce()) {
            params.put("code_challenge", challenge(verifier));
            params.put("code_challenge_method", "S256");
        }
        log.info("OAuth sign-in started: serverId={}, provider={}", serverId, providerId);
        return new Started(attemptId, appendQuery(provider.getAuthorizeUrl(), form(params)), cookieName, cookieValue);
    }

    /** Handles the provider's redirect. Never throws for user-caused failures; they become the outcome. */
    public Attempt complete(String state, String code, String error, String errorDescription, String browserBinding) {
        purgeExpired();
        Pending started = state == null ? null : pending.get(state);
        if (started == null) {
            return new Attempt(state, null, null, AttemptStatus.FAILED,
                    "This sign-in link is invalid or has expired. Start the sign-in again.");
        }
        if (browserBinding == null || !MessageDigest.isEqual(
                started.browserBindingHash().getBytes(StandardCharsets.US_ASCII),
                sha256(browserBinding).getBytes(StandardCharsets.US_ASCII))) {
            return new Attempt(started.attemptId(), started.serverId(), started.providerId(), AttemptStatus.FAILED,
                    "This sign-in did not originate from this browser. Start the sign-in again.");
        }
        if (!pending.remove(state, started)) {
            return new Attempt(started.attemptId(), started.serverId(), started.providerId(), AttemptStatus.FAILED,
                    "This sign-in link has already been used. Start the sign-in again.");
        }

        EMcpServer server = repository.findById(started.serverId()).orElse(null);
        if (server == null) {
            return finish(state, started, AttemptStatus.FAILED, "The connection was removed during sign-in.", null);
        }
        if (error != null && !error.isBlank()) {
            String reason = "access_denied".equals(error)
                    ? "Sign-in was cancelled or access was denied."
                    : "The provider reported an error" + safeErrorCode(error) + ".";
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
            storeTokens(server, requestToken(provider, body), false);
            log.info("OAuth sign-in completed: serverId={}, provider={}", server.getId(), started.providerId());
            return finish(state, started, AttemptStatus.CONNECTING,
                    "Sign-in succeeded. Verifying the authenticated MCP connection and discovering tools…", null);
        } catch (OAuthException exception) {
            log.warn("OAuth token exchange failed: serverId={}, provider={}, cause={}",
                    server.getId(), started.providerId(), SafeDiagnostic.message(exception, 500));
            return finish(state, started, AttemptStatus.FAILED, SafeDiagnostic.message(exception, 500), server);
        }
    }

    /** Kept for direct callers that cannot supply browser binding; callbacks must use the bound overload. */
    public Attempt complete(String state, String code, String error, String errorDescription) {
        return complete(state, code, error, errorDescription, null);
    }

    public Attempt attempt(String attemptId) {
        purgeExpired();
        Attempt attempt = attemptId == null ? null : outcomes.get(attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired sign-in attempt");
        }
        return attempt;
    }

    /** Cancels only an authorization that has not reached its callback; in-flight token/MCP work completes. */
    public Attempt cancel(String attemptId) {
        purgeExpired();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending started = entry.getValue();
            if (!started.attemptId().equals(attemptId)) continue;
            Attempt current = outcomes.get(attemptId);
            if (current == null || current.status() != AttemptStatus.PENDING) break;
            if (pending.remove(entry.getKey(), started)) {
                Attempt cancelled = new Attempt(attemptId, started.serverId(), started.providerId(),
                        AttemptStatus.FAILED, "The sign-in window was closed before authorization completed.");
                outcomes.replace(attemptId, current, cancelled);
                return outcomes.get(attemptId);
            }
        }
        return attempt(attemptId);
    }

    /** Callback completion is not successful until the authenticated MCP handshake and discovery finish. */
    public Attempt connectionResult(String attemptId, boolean connected, String message) {
        Attempt current = attempt(attemptId);
        if (current.status() != AttemptStatus.CONNECTING) return current;
        Attempt result = new Attempt(current.id(), current.serverId(), current.providerId(),
                connected ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED, message);
        outcomes.replace(attemptId, current, result);
        return outcomes.get(attemptId);
    }

    /**
     * The token to send for an OAUTH connection, refreshed first when it is about to expire and a
     * refresh token exists. Throws when the connection has no usable token (sign in again).
     */
    public String accessToken(EMcpServer server) {
        synchronized (server) {
            return accessTokenLocked(server);
        }
    }

    /** Best-effort provider revocation before a saved OAuth connection is deleted. */
    public void revoke(EMcpServer server) {
        if (server.getAuthType() != McpAuthType.OAUTH || !server.hasCredential()) return;
        OAuthProperties.Provider provider = properties.getProviders().get(server.getOauthProvider());
        if (provider == null || provider.getRevocationUrl() == null || provider.getRevocationUrl().isBlank()) return;
        String encryptedToken = server.getEncryptedRefreshToken() == null
                ? server.getEncryptedSecret() : server.getEncryptedRefreshToken();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(provider.getRevocationUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                            "token", encryption.decrypt(encryptedToken)))))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                log.info("OAuth credential revocation completed: serverId={}, provider={}", server.getId(), server.getOauthProvider());
            } else {
                log.warn("OAuth credential revocation was rejected: serverId={}, provider={}, status={}",
                        server.getId(), server.getOauthProvider(), response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("OAuth credential revocation was interrupted: serverId={}, provider={}", server.getId(), server.getOauthProvider());
        } catch (Exception exception) {
            log.warn("OAuth credential revocation failed: serverId={}, provider={}, cause={}",
                    server.getId(), server.getOauthProvider(), exception.getClass().getSimpleName());
        }
    }

    private String accessTokenLocked(EMcpServer server) {
        if (!server.hasCredential()) {
            throw new IllegalStateException("Not signed in yet. Use 'Sign in' on this connection first.");
        }
        OffsetDateTime expiresAt = server.getTokenExpiresAt();
        boolean expiring = expiresAt != null && expiresAt.isBefore(OffsetDateTime.now().plus(REFRESH_MARGIN));
        if (expiring) {
            if (server.getEncryptedRefreshToken() == null) {
                throw reauthorizationRequired(server, "The sign-in has expired. Re-authorize this connection.");
            }
            OAuthProperties.Provider provider = properties.getProviders().get(server.getOauthProvider());
            if (provider == null || !provider.isConfigured()) {
                throw reauthorizationRequired(server, "The OAuth provider is no longer configured. Check server settings and re-authorize.");
            }
            Map<String, String> body = new LinkedHashMap<>();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", encryption.decrypt(server.getEncryptedRefreshToken()));
            try {
                storeTokens(server, requestToken(provider, body), true);
                log.info("OAuth token refreshed: serverId={}", server.getId());
            } catch (OAuthException exception) {
                throw reauthorizationRequired(server,
                        "Refreshing the sign-in failed: " + SafeDiagnostic.message(exception, 300)
                                + " Re-authorize this connection.");
            }
        }
        return encryption.decrypt(server.getEncryptedSecret());
    }

    private IllegalStateException reauthorizationRequired(EMcpServer server, String message) {
        server.markReauthorizationRequired(message);
        repository.save(server);
        return new IllegalStateException(message);
    }

    // ---------------------------------------------------------------- internals

    private void storeTokens(EMcpServer server, JsonNode token, boolean refreshing) {
        String access = token.path("access_token").asText("");
        if (access.isBlank()) {
            throw new OAuthException("The provider returned no access token.");
        }
        String tokenType = token.path("token_type").asText("");
        if (!tokenType.isBlank() && !"bearer".equalsIgnoreCase(tokenType)) {
            throw new OAuthException("The provider returned an unsupported token type.");
        }
        String refresh = token.path("refresh_token").asText("");
        long expiresIn = token.path("expires_in").asLong(0);
        OffsetDateTime expiresAt = expiresIn > 0 ? OffsetDateTime.now().plusSeconds(expiresIn)
                : refreshing ? server.getTokenExpiresAt() : null;
        server.storeOAuthToken(
                encryption.encrypt(access),
                refresh.isBlank() ? null : encryption.encrypt(refresh),
                expiresAt);
        repository.save(server);
    }

    private JsonNode requestToken(OAuthProperties.Provider provider, Map<String, String> body) {
        body.putAll(provider.getTokenParameters());
        boolean basic = "client_secret_basic".equalsIgnoreCase(provider.getClientAuthentication());
        if (basic) {
            body.remove("client_id");
            body.remove("client_secret");
        } else {
            body.put("client_id", provider.getClientId());
            body.put("client_secret", provider.getClientSecret());
        }
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(provider.getTokenUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded");
            if (basic) {
                String credentials = URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8) + ":"
                        + URLEncoder.encode(provider.getClientSecret(), StandardCharsets.UTF_8);
                requestBuilder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(form(body))).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream bodyStream = response.body()) {
                byte[] bytes = bodyStream == null ? new byte[0] : bodyStream.readNBytes(65_537);
                if (bytes.length > 65_536) {
                    throw new OAuthException("The provider's token response exceeded the allowed size.");
                }
                responseBody = new String(bytes, StandardCharsets.UTF_8);
            }
            JsonNode json = responseBody.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(responseBody);
            // GitHub answers errors with HTTP 200 and an "error" field.
            if (json.hasNonNull("error")) {
                throw new OAuthException("The provider rejected the sign-in" + safeErrorCode(json.path("error").asText()) + ".");
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
        Attempt attempt = new Attempt(started.attemptId(), started.serverId(), started.providerId(), status, message);
        outcomes.put(started.attemptId(), attempt);
        outcomeCreatedAt.put(started.attemptId(), Instant.now());
        return attempt;
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minusSeconds(properties.getAttemptTtlSeconds());
        pending.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
        outcomeCreatedAt.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        outcomes.keySet().retainAll(outcomeCreatedAt.keySet());
        if (outcomes.size() > 1000) {
            java.util.Set<String> activeAttempts = pending.values().stream()
                    .map(Pending::attemptId).collect(java.util.stream.Collectors.toSet());
            outcomeCreatedAt.entrySet().stream()
                    .filter(entry -> !activeAttempts.contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByValue())
                    .limit(outcomes.size() - 1000L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(id -> {
                        outcomeCreatedAt.remove(id);
                        outcomes.remove(id);
                    });
        }
    }

    private static String displayName(String id, OAuthProperties.Provider provider) {
        return provider.getDisplayName() == null ? id : provider.getDisplayName();
    }

    private void validateProvider(String id, OAuthProperties.Provider provider) {
        validateEndpoint(provider.getAuthorizeUrl(), "authorization", id);
        validateEndpoint(provider.getTokenUrl(), "token", id);
        if (provider.getRevocationUrl() != null && !provider.getRevocationUrl().isBlank()) {
            validateEndpoint(provider.getRevocationUrl(), "revocation", id);
        }
        String authentication = provider.getClientAuthentication();
        if (!"client_secret_post".equalsIgnoreCase(authentication)
                && !"client_secret_basic".equalsIgnoreCase(authentication)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "OAuth provider '" + id + "' has an unsupported client authentication method.");
        }
        if (containsReserved(provider.getAuthorizationParameters(), List.of(
                "response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "OAuth provider '" + id + "' has authorization parameters that override protocol fields.");
        }
        if (containsReserved(provider.getTokenParameters(), List.of(
                "grant_type", "code", "redirect_uri", "code_verifier", "refresh_token", "client_id", "client_secret"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "OAuth provider '" + id + "' has token parameters that override protocol fields.");
        }
    }

    private static boolean containsReserved(Map<String, String> values, List<String> reserved) {
        return values != null && values.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || !entry.getKey().matches("[A-Za-z0-9_.-]{1,80}") || entry.getValue() == null
                || reserved.stream().anyMatch(entry.getKey()::equalsIgnoreCase));
    }

    private static void validateEndpoint(String value, String kind, String providerId) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null
                    || !("https".equalsIgnoreCase(scheme) || loopbackHttp)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "OAuth provider '" + providerId + "' has an invalid " + kind
                            + " endpoint. Use HTTPS (HTTP is allowed only for loopback test endpoints).");
        }
    }

    private static void validateRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getRawQuery() != null || !("https".equalsIgnoreCase(scheme) || loopbackHttp)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "connectai.oauth.redirect-uri must be HTTPS (HTTP is allowed only for loopback development) and must not include query or fragment data.");
        }
    }

    private static List<String> scopes(OAuthProperties.Provider provider) {
        if (provider.getScopes() == null || provider.getScopes().isBlank()) return List.of();
        return List.of(provider.getScopes().trim().split("\\s+"));
    }

    private static String safeErrorCode(String error) {
        if (error == null || error.isBlank()) return "";
        return switch (error) {
            case "access_denied", "invalid_request", "unauthorized_client", "unsupported_response_type",
                    "invalid_scope", "server_error", "temporarily_unavailable", "invalid_grant",
                    "invalid_client", "bad_verification_code" -> " (" + error + ")";
            default -> "";
        };
    }

    private static String appendQuery(String endpoint, String query) {
        return endpoint + (endpoint.contains("?") ? "&" : "?") + query;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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
