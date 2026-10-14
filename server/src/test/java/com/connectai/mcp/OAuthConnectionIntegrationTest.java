package com.connectai.mcp;

import com.connectai.mcp.client.McpConnectionService;
import com.connectai.mcp.client.McpToolDiscoveryService;
import com.connectai.mcp.oauth.OAuthService;
import com.connectai.mcp.repository.McpServerRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth sign-in for an MCP connection, end to end inside the test: a FAKE OAuth provider (token
 * endpoint below, test-only) and the real in-process MCP server. The browser redirect is simulated
 * by calling the callback with the code the provider would have issued.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "connectai.tools.mode=mcp",
                "connectai.llm.enabled=false",
                "connectai.seed-demo-data=false",
                "server.error.include-message=always",
                "connectai.oauth.redirect-uri=http://localhost/api/oauth/callback",
                "connectai.oauth.providers.fakegh.display-name=Fake GitHub",
                "connectai.oauth.providers.fakegh.authorize-url=https://provider.example/authorize",
                "connectai.oauth.providers.fakegh.client-id=test-client",
                "connectai.oauth.providers.fakegh.client-secret=test-secret",
                "connectai.oauth.providers.fakegh.revocation-url=https://provider.example/revoke",
                "connectai.oauth.providers.fakegh.scopes=repo read:user",
                "connectai.oauth.providers.google.display-name=Google Drive",
                "connectai.oauth.providers.google.authorize-url=https://accounts.google.com/o/oauth2/v2/auth",
                "connectai.oauth.providers.google.client-id=test-client",
                "connectai.oauth.providers.google.client-secret=test-secret",
                "connectai.oauth.providers.google.scopes=https://www.googleapis.com/auth/drive.readonly",
                "connectai.oauth.providers.google.access-type=offline",
                "connectai.oauth.providers.slack.display-name=Slack",
                "connectai.oauth.providers.slack.authorize-url=https://slack.com/oauth/v2_user/authorize",
                "connectai.oauth.providers.slack.client-id=slack-test-client",
                "connectai.oauth.providers.slack.client-secret=slack-test-secret",
                "connectai.oauth.providers.slack.redirect-uri=https://slack.example/api/oauth/callback",
                "connectai.oauth.providers.slack.require-https-redirect=true",
                // Regression: Slack must accept its documented `user` response even if an older
                // runtime configuration still carries the generic `bearer` expectation.
                "connectai.oauth.providers.slack.expected-token-type=bearer",
                "connectai.oauth.providers.slack.resource=https://mcp.slack.com",
                "connectai.oauth.providers.slack.scopes=search:read.public,search:read.private,channels:history,groups:history",
                "connectai.oauth.providers.grafana.authorize-url=https://grafana.example/authorize",
                "connectai.oauth.providers.grafana.token-url=https://grafana.example/token",
                "connectai.oauth.providers.grafana.client-id=grafana-test-client",
                "connectai.oauth.providers.grafana.client-secret=grafana-test-secret",
                "connectai.oauth.providers.grafana.scopes=grafana:dashboards:read",
                "connectai.oauth.providers.grafana.redirect-uri=https://grafana.example/api/oauth/callback",
                "connectai.oauth.providers.unconfigured.authorize-url=https://other.example/authorize",
                "connectai.oauth.providers.badendpoint.display-name=Invalid endpoint",
                "connectai.oauth.providers.badendpoint.authorize-url=http://provider.example/authorize",
                "connectai.oauth.providers.badendpoint.token-url=https://provider.example/token",
                "connectai.oauth.providers.badendpoint.client-id=test-client",
                "connectai.oauth.providers.badendpoint.client-secret=test-secret",
                "connectai.oauth.providers.fakebasic.display-name=Fake Basic Provider",
                "connectai.oauth.providers.fakebasic.authorize-url=https://provider.example/authorize",
                "connectai.oauth.providers.fakebasic.client-id=test:client",
                "connectai.oauth.providers.fakebasic.client-secret=test+secret",
                "connectai.oauth.providers.fakebasic.client-authentication=client_secret_basic"
        })
@Import({McpInvestigationPipelineIntegrationTest.TestMcpServer.class, OAuthConnectionIntegrationTest.FakeProvider.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthConnectionIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    /** What the fake provider expects and issues. */
    static final Map<String, String> CODE_CHALLENGES = new ConcurrentHashMap<>();
    static final AtomicReference<String> NEXT_EXPIRES_IN = new AtomicReference<>(null);
    static final AtomicReference<String> LAST_MCP_AUTHORIZATION = new AtomicReference<>();
    static final AtomicReference<Boolean> ROTATING_REFRESH_TOKENS = new AtomicReference<>(false);
    static final AtomicReference<Boolean> FAIL_REFRESH = new AtomicReference<>(false);
    static final AtomicReference<String> LAST_ISSUED_ACCESS_TOKEN = new AtomicReference<>();
    static final AtomicInteger ROTATING_REFRESH_COUNT = new AtomicInteger();
    static final AtomicReference<String> LAST_TOKEN_AUTHORIZATION = new AtomicReference<>();
    static final AtomicReference<String> LAST_TOKEN_REDIRECT_URI = new AtomicReference<>();
    static final AtomicReference<Boolean> LAST_REVOKED_TOKEN_VALID = new AtomicReference<>(false);
    static final AtomicReference<String> SLACK_TOKEN_TYPE = new AtomicReference<>("user");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    McpServerRepository servers;

    @Autowired
    OAuthService oauth;

    @Autowired
    McpConnectionService connections;

    @Autowired
    McpToolDiscoveryService discovery;

    private String serverId;

    @Test
    @Order(1)
    void providersShowWhichAreConfigured() {
        var providers = http.exchange("/api/oauth/providers", HttpMethod.GET, null,
                new ParameterizedTypeReference<java.util.List<Map<String, Object>>>() { }).getBody();
        assertThat(providers).anyMatch(p -> "fakegh".equals(p.get("id")) && Boolean.TRUE.equals(p.get("configured")));
        assertThat(providers).anyMatch(p -> "google".equals(p.get("id")) && Boolean.TRUE.equals(p.get("configured")));
        assertThat(providers).anyMatch(p -> "slack".equals(p.get("id")) && Boolean.TRUE.equals(p.get("configured"))
                && "https://slack.example/api/oauth/callback".equals(p.get("redirectUri")));
        assertThat(providers).anyMatch(p -> "grafana".equals(p.get("id")) && Boolean.TRUE.equals(p.get("configured"))
                && "https://grafana.example/api/oauth/callback".equals(p.get("redirectUri")));
        assertThat(providers).anySatisfy(p -> {
            if (!"slack".equals(p.get("id"))) return;
            assertThat(p.get("scopes")).isEqualTo(java.util.List.of(
                    "search:read.public", "search:read.private", "channels:history", "groups:history"));
        });
        assertThat(providers).anyMatch(p -> "google".equals(p.get("id"))
                && ((java.util.List<?>) p.get("scopes")).contains("https://www.googleapis.com/auth/drive.readonly"));
        assertThat(providers).anyMatch(p -> "unconfigured".equals(p.get("id")) && Boolean.FALSE.equals(p.get("configured")));
        var catalog = http.exchange("/api/catalog", HttpMethod.GET, null,
                new ParameterizedTypeReference<java.util.List<Map<String, Object>>>() { }).getBody();
        assertThat(catalog).anySatisfy(item -> assertThat(((Map<?, ?>) item.get("connector")).get("key"))
                .isEqualTo("google-drive"));
        assertThat(catalog).anySatisfy(item -> {
            @SuppressWarnings("unchecked") Map<String, Object> connector = (Map<String, Object>) item.get("connector");
            if (!"google-drive".equals(connector.get("key"))) return;
            @SuppressWarnings("unchecked") var methods = (java.util.List<Map<String, Object>>) connector.get("methods");
            assertThat(methods).anySatisfy(method -> assertThat(method)
                    .containsEntry("id", "OAUTH_APP").containsEntry("supported", true));
            assertThat(item).containsEntry("adminSetupRequired", false);
        });
        assertThat(catalog).anySatisfy(item -> {
            @SuppressWarnings("unchecked") Map<String, Object> connector = (Map<String, Object>) item.get("connector");
            if (!"slack".equals(connector.get("key"))) return;
            @SuppressWarnings("unchecked") var methods = (java.util.List<Map<String, Object>>) connector.get("methods");
            assertThat(methods).anySatisfy(method -> assertThat(method)
                    .containsEntry("id", "OAUTH_APP").containsEntry("supported", true));
            assertThat(item).containsEntry("adminSetupRequired", false);
        });
    }

    @Test
    @Order(2)
    void oauthRegistrationIsValidated() {
        assertThat(register("oauth-unknown", "nope").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(register("oauth-unconfigured", "unconfigured").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(register("oauth-invalid-endpoint", "badendpoint").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map<String, Object>> created = register("GitHub - OAuth", "fakegh");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("authType", "OAUTH").containsEntry("oauthProvider", "fakegh")
                .containsEntry("secretConfigured", false);
        serverId = created.getBody().get("id").toString();

        ResponseEntity<Map<String, Object>> early = post("/api/tools/" + serverId + "/connect");
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().get("message").toString()).contains("not signed in yet");
    }

    @Test
    @Order(3)
    void signInStoresTheTokenAndTheConnectionUsesIt() {
        Map<String, Object> started = startOAuth(serverId);
        String url = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        assertThat(url).startsWith("https://provider.example/authorize?");
        assertThat(query).containsEntry("client_id", "test-client").containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256").containsKey("code_challenge").containsKey("state");
        assertThat(decode(query.get("redirect_uri"))).isEqualTo("http://localhost/api/oauth/callback");
        assertThat(decode(query.get("scope"))).isEqualTo("repo read:user");
        String state = query.get("state");
        String attemptId = started.get("attemptId").toString();
        assertThat(attemptId).isNotEqualTo(state);
        CODE_CHALLENGES.put("good-code", query.get("code_challenge"));

        assertThat(attempt(attemptId).get("status")).isEqualTo("PENDING");

        ResponseEntity<String> page = callback("/api/oauth/callback?code=good-code&state=" + state, state);
        assertThat(page.getBody()).contains("Access granted");
        assertThat(attempt(attemptId).get("status")).isEqualTo("SUCCEEDED");
        assertThat(connections.status(UUID.fromString(serverId)).initialized()).isTrue();
        assertThat(discovery.discover(UUID.fromString(serverId)).tools()).isNotEmpty();

        Map<String, Object> tool = http.exchange("/api/tools/" + serverId, HttpMethod.GET, null, MAP).getBody();
        assertThat(tool).containsEntry("secretConfigured", true);
        assertThat(http.getForEntity("/api/tools", String.class).getBody()).doesNotContain("gho_first_token");

        assertThat(post("/api/tools/" + serverId + "/connect").getBody()).containsEntry("status", "CONNECTED");
        assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer gho_first_token");

        // The state is single-use.
        assertThat(callback("/api/oauth/callback?code=good-code&state=" + state, state).getBody())
                .contains("invalid or has expired");
        post("/api/tools/" + serverId + "/disconnect");
    }

    @Test
    @Order(4)
    void deniedAndRejectedSignInsFailHonestly() {
        String denied = startState();
        String deniedPage = callback("/api/oauth/callback?error=access_denied&error_description=secret-value&state=" + denied, denied).getBody();
        assertThat(deniedPage).contains("Sign-in failed").contains("cancelled or access was denied").doesNotContain("secret-value");
        assertThat(attempt(attemptByState.get(denied))).containsEntry("status", "FAILED");

        String badCode = startState();
        callback("/api/oauth/callback?code=wrong-code&state=" + badCode, badCode);
        assertThat(attempt(attemptByState.get(badCode)).get("message").toString())
                .contains("bad_verification_code").doesNotContain("secret-value");

        // A failed re-sign-in keeps the earlier token.
        assertThat(servers.findById(UUID.fromString(serverId)).orElseThrow().hasCredential()).isTrue();
    }

    @Test
    @Order(5)
    void expiringTokensAreRefreshedBeforeConnecting() {
        NEXT_EXPIRES_IN.set("10"); // inside the refresh margin, with a refresh token
        String state = startState();
        CODE_CHALLENGES.put("good-code", challengeOf(state));
        callback("/api/oauth/callback?code=good-code&state=" + state, state);
        assertThat(attempt(attemptByState.get(state))).containsEntry("status", "SUCCEEDED");
        NEXT_EXPIRES_IN.set(null);

        assertThat(post("/api/tools/" + serverId + "/connect").getBody()).containsEntry("status", "CONNECTED");
        assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer gho_refreshed_token");
        assertThat(servers.findById(UUID.fromString(serverId)).orElseThrow().getEncryptedRefreshToken()).isNotBlank();
        post("/api/tools/" + serverId + "/disconnect");
    }

    @Test
    @Order(6)
    void googleDriveUsesReadOnlyOfflineOAuthAndRefreshesForLaterMcpRequests() {
        ROTATING_REFRESH_TOKENS.set(true);
        ROTATING_REFRESH_COUNT.set(0);
        LAST_ISSUED_ACCESS_TOKEN.set(null);
        NEXT_EXPIRES_IN.set("10");
        String googleServerId = null;
        try {
            ResponseEntity<Map<String, Object>> created = register("Google Drive - OAuth", "google");
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            googleServerId = created.getBody().get("id").toString();

            Map<String, Object> started = startOAuth(googleServerId);
            String authorizationUrl = started.get("authorizationUrl").toString();
            Map<String, String> query = UriComponentsBuilder.fromUriString(authorizationUrl)
                    .build().getQueryParams().toSingleValueMap();
            assertThat(authorizationUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
            assertThat(query).containsEntry("access_type", "offline")
                    .containsEntry("code_challenge_method", "S256").containsKey("state");
            assertThat(decode(query.get("scope"))).isEqualTo("https://www.googleapis.com/auth/drive.readonly");
            assertThat(decode(query.get("redirect_uri"))).isEqualTo("http://localhost/api/oauth/callback");

            CODE_CHALLENGES.put("google-code", query.get("code_challenge"));
            String state = query.get("state");
            callback("/api/oauth/callback?code=google-code&state=" + state, state);
            assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "SUCCEEDED");
            assertThat(connections.status(UUID.fromString(googleServerId)).initialized()).isTrue();
            assertThat(discovery.discover(UUID.fromString(googleServerId)).tools()).isNotEmpty();
            String originalEncryptedRefreshToken = servers.findById(UUID.fromString(googleServerId)).orElseThrow()
                    .getEncryptedRefreshToken();

            NEXT_EXPIRES_IN.set(null);
            UUID id = UUID.fromString(googleServerId);
            assertThat(connections.connect(id).status().name()).isEqualTo("CONNECTED");
            assertThat(discovery.discover(id).tools()).isNotEmpty();
            assertThat(ROTATING_REFRESH_COUNT.get()).isGreaterThan(0);
            assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer " + LAST_ISSUED_ACCESS_TOKEN.get());
            assertThat(servers.findById(id).orElseThrow().getEncryptedRefreshToken())
                    .isEqualTo(originalEncryptedRefreshToken).isNotBlank();
        } finally {
            NEXT_EXPIRES_IN.set(null);
            ROTATING_REFRESH_TOKENS.set(false);
            if (googleServerId != null) {
                try {
                    connections.disconnect(UUID.fromString(googleServerId));
                } catch (RuntimeException ignored) {
                    // The test may fail before a runtime connection is registered.
                }
            }
        }
    }

    @Test
    @Order(7)
    void slackUserOAuthUsesHttpsCallbackResourceAndDiscoversTools() {
        ResponseEntity<Map<String, Object>> created = register("Slack - OAuth", "slack");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(created.getBody().get("id").toString());
        Map<String, Object> started = startOAuth(id.toString());
        String authorizationUrl = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(authorizationUrl).build()
                .getQueryParams().toSingleValueMap();
        assertThat(authorizationUrl).startsWith("https://slack.com/oauth/v2_user/authorize?");
        assertThat(query).containsEntry("client_id", "slack-test-client")
                .containsEntry("code_challenge_method", "S256");
        assertThat(decode(query.get("resource"))).isEqualTo("https://mcp.slack.com");
        assertThat(decode(query.get("scope")))
                .isEqualTo("search:read.public,search:read.private,channels:history,groups:history");
        assertThat(decode(query.get("redirect_uri")))
                .isEqualTo("https://slack.example/api/oauth/callback");

        CODE_CHALLENGES.put("slack-good-code", query.get("code_challenge"));
        callback("/api/oauth/callback?code=slack-good-code&state=" + query.get("state"), query.get("state"));

        assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "SUCCEEDED");
        assertThat(connections.status(id).initialized()).isTrue();
        assertThat(discovery.discover(id).tools()).isNotEmpty();
        assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer xoxp_fake_slack_user_token");
        var persisted = servers.findById(id).orElseThrow();
        assertThat(persisted.hasCredential()).isTrue();
        assertThat(persisted.getEncryptedSecret()).doesNotContain("xoxp_fake_slack_user_token");
        assertThat(http.exchange("/api/tools/" + id, HttpMethod.GET, null, MAP).getBody().toString())
                .doesNotContain("xoxp_fake_slack_user_token", "slack-test-secret");
        connections.disconnect(id);
        connections.remove(id);
    }

    @Test
    @Order(10)
    void grafanaAuthorizationUsesItsRegisteredProviderSpecificCallback() {
        ResponseEntity<Map<String, Object>> created = register("Grafana - OAuth", "grafana");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(created.getBody().get("id").toString());

        Map<String, Object> started = startOAuth(id.toString());
        String authorizationUrl = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(authorizationUrl).build()
                .getQueryParams().toSingleValueMap();

        assertThat(authorizationUrl).startsWith("https://grafana.example/authorize?");
        assertThat(query).containsEntry("client_id", "grafana-test-client")
                .containsEntry("code_challenge_method", "S256");
        assertThat(decode(query.get("redirect_uri")))
                .isEqualTo("https://grafana.example/api/oauth/callback");

        CODE_CHALLENGES.put("grafana-good-code", query.get("code_challenge"));
        callback("/api/oauth/callback?code=grafana-good-code&state=" + query.get("state"), query.get("state"));

        assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "SUCCEEDED");
        assertThat(LAST_TOKEN_REDIRECT_URI.get()).isEqualTo("https://grafana.example/api/oauth/callback");
        connections.disconnect(id);
        connections.remove(id);
    }

    @Test
    @Order(8)
    void slackOkFalseTokenResponseDoesNotPersistCredential() {
        ResponseEntity<Map<String, Object>> created = register("Slack - Failed OAuth", "slack");
        UUID id = UUID.fromString(created.getBody().get("id").toString());
        Map<String, Object> started = startOAuth(id.toString());
        Map<String, String> query = UriComponentsBuilder.fromUriString(started.get("authorizationUrl").toString())
                .build().getQueryParams().toSingleValueMap();
        CODE_CHALLENGES.put("slack-error-code", query.get("code_challenge"));

        callback("/api/oauth/callback?code=slack-error-code&state=" + query.get("state"), query.get("state"));

        assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "FAILED");
        assertThat(servers.findById(id).orElseThrow().hasCredential()).isFalse();
        assertThat(servers.findById(id).orElseThrow().getLastError()).contains("invalid_code");
        connections.remove(id);
    }

    @Test
    @Order(13)
    void slackUserEndpointAlsoAcceptsGenericBearerTokenTypeLabel() {
        ResponseEntity<Map<String, Object>> created = register("Slack - Bearer Label", "slack");
        UUID id = UUID.fromString(created.getBody().get("id").toString());
        Map<String, Object> started = startOAuth(id.toString());
        Map<String, String> query = UriComponentsBuilder.fromUriString(started.get("authorizationUrl").toString())
                .build().getQueryParams().toSingleValueMap();
        CODE_CHALLENGES.put("slack-bearer-code", query.get("code_challenge"));

        SLACK_TOKEN_TYPE.set("bearer");
        try {
            callback("/api/oauth/callback?code=slack-bearer-code&state=" + query.get("state"), query.get("state"));
        } finally {
            SLACK_TOKEN_TYPE.set("user");
        }

        assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "SUCCEEDED");
        assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer xoxp_fake_slack_user_token");
        connections.disconnect(id);
        connections.remove(id);
    }

    @Test
    @Order(14)
    void slackUserEndpointRejectsBotTokensWithSafeTokenTypeDiagnostic() {
        ResponseEntity<Map<String, Object>> created = register("Slack - Bot Token Rejection", "slack");
        UUID id = UUID.fromString(created.getBody().get("id").toString());
        Map<String, Object> started = startOAuth(id.toString());
        Map<String, String> query = UriComponentsBuilder.fromUriString(started.get("authorizationUrl").toString())
                .build().getQueryParams().toSingleValueMap();
        CODE_CHALLENGES.put("slack-bot-code", query.get("code_challenge"));

        SLACK_TOKEN_TYPE.set("bot");
        try {
            callback("/api/oauth/callback?code=slack-bot-code&state=" + query.get("state"), query.get("state"));
        } finally {
            SLACK_TOKEN_TYPE.set("user");
        }

        assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "FAILED")
                .containsEntry("message", "The provider returned an unsupported token type (bot).");
        assertThat(servers.findById(id).orElseThrow().hasCredential()).isFalse();
        connections.remove(id);
    }

    @Test
    @Order(9)
    void crossHostCallbackRelaysBackToTheInitiatingBrowserAndKeepsCookieBinding() {
        String state = startState();
        String attemptId = attemptByState.get(state);
        CODE_CHALLENGES.put("bound-code", challengeOf(state));
        ResponseEntity<String> unbound = http.getForEntity(
                "/api/oauth/callback?code=bound-code&state=" + state, String.class);
        assertThat(unbound.getBody()).contains("connect-ai-oauth-callback")
                .contains("data-target-origin=\"http://localhost:5173\"")
                .contains("data-code=\"bound-code\"");
        assertThat(attempt(attemptId)).containsEntry("status", "PENDING");

        HttpHeaders untrustedHeaders = new HttpHeaders();
        untrustedHeaders.setOrigin("https://attacker.example");
        untrustedHeaders.add(HttpHeaders.COOKIE, bindingCookieByState.get(state));
        Map<String, String> completion = Map.of("state", state, "code", "bound-code");
        ResponseEntity<String> rejected = http.exchange("/api/oauth/callback/complete", HttpMethod.POST,
                new HttpEntity<>(completion, untrustedHeaders), String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attempt(attemptId)).containsEntry("status", "PENDING");

        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.add(HttpHeaders.COOKIE, bindingCookieByState.get(state));
        ResponseEntity<Map<String, Object>> completed = http.exchange("/api/oauth/callback/complete", HttpMethod.POST,
                new HttpEntity<>(completion, headers), MAP);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).containsEntry("status", "SUCCEEDED");
        assertThat(attempt(attemptId)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @Order(10)
    void providerCanUseHttpBasicClientAuthentication() {
        ResponseEntity<Map<String, Object>> created = register("Basic OAuth", "fakebasic");
        String id = created.getBody().get("id").toString();
        Map<String, Object> started = startOAuth(id);
        String url = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        String state = query.get("state");
        CODE_CHALLENGES.put("basic-code", query.get("code_challenge"));
        assertThat(callback("/api/oauth/callback?code=basic-code&state=" + state, state).getBody())
                .contains("Access granted");
        assertThat(LAST_TOKEN_AUTHORIZATION.get()).startsWith("Basic ");
        assertThat(attempt(started.get("attemptId").toString())).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @Order(11)
    void removingOAuthConnectionAttemptsConfiguredProviderRevocation() {
        ResponseEntity<Map<String, Object>> created = register("Revocation test", "fakegh");
        String id = created.getBody().get("id").toString();
        Map<String, Object> started = startOAuth(id);
        String url = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        String state = query.get("state");
        CODE_CHALLENGES.put("revoke-code", query.get("code_challenge"));
        callback("/api/oauth/callback?code=revoke-code&state=" + state, state);
        LAST_REVOKED_TOKEN_VALID.set(false);
        connections.disconnect(UUID.fromString(id));

        connections.remove(UUID.fromString(id));

        assertThat(LAST_REVOKED_TOKEN_VALID.get()).isTrue();
        assertThat(servers.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    @Order(12)
    void failedRefreshMarksConnectionAsReauthorizationRequiredAndSignInRecoversIt() {
        ResponseEntity<Map<String, Object>> created = register("Refresh failure", "fakegh");
        UUID id = UUID.fromString(created.getBody().get("id").toString());
        NEXT_EXPIRES_IN.set("10");
        FAIL_REFRESH.set(true);
        String state = startStateFor(id.toString());
        CODE_CHALLENGES.put("expired-token-code", challengeOf(state));
        callback("/api/oauth/callback?code=expired-token-code&state=" + state, state);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> oauth.accessToken(servers.findById(id).orElseThrow()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Re-authorize");
        } finally {
            FAIL_REFRESH.set(false);
            NEXT_EXPIRES_IN.set(null);
        }
        assertThat(servers.findById(id).orElseThrow().getStatus().name()).isEqualTo("REAUTH_REQUIRED");
        assertThat(connections.status(id).status().name()).isEqualTo("REAUTH_REQUIRED");

        String retryState = startStateFor(id.toString());
        CODE_CHALLENGES.put("retry-signin-code", challengeOf(retryState));
        callback("/api/oauth/callback?code=retry-signin-code&state=" + retryState, retryState);
        assertThat(servers.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONNECTED");
        assertThat(servers.findById(id).orElseThrow().getLastError()).isNull();
    }

    @AfterAll
    void cleanup() {
        servers.findAll().forEach(s -> {
            try {
                connections.disconnect(s.getId());
            } catch (RuntimeException ignored) {
                // best effort
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private final Map<String, String> challengeByState = new HashMap<>();
    private final Map<String, String> attemptByState = new HashMap<>();
    private final Map<String, String> bindingCookieByState = new HashMap<>();

    private String startState() {
        return startStateFor(serverId);
    }

    private String startStateFor(String id) {
        Map<String, Object> started = startOAuth(id);
        String url = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        challengeByState.put(query.get("state"), query.get("code_challenge"));
        attemptByState.put(query.get("state"), started.get("attemptId").toString());
        return query.get("state");
    }

    private String challengeOf(String state) {
        return challengeByState.get(state);
    }

    private Map<String, Object> attempt(String state) {
        return http.exchange("/api/oauth/attempts/" + state, HttpMethod.GET, null, MAP).getBody();
    }

    private Map<String, Object> startOAuth(String id) {
        ResponseEntity<Map<String, Object>> response = http.exchange(
                "/api/tools/" + id + "/oauth/start", HttpMethod.POST, null, MAP);
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("HttpOnly").contains("Path=/api/oauth/callback");
        assertThat(cookie).contains(cookie.contains("Secure") ? "SameSite=None" : "SameSite=Lax");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        String value = cookie == null ? "" : cookie.split(";", 2)[0];
        String authorizationUrl = response.getBody().get("authorizationUrl").toString();
        String state = UriComponentsBuilder.fromUriString(authorizationUrl).build().getQueryParams().getFirst("state");
        bindingCookieByState.put(state, value);
        assertThat(response.getBody()).doesNotContainValue(value.contains("=") ? value.substring(value.indexOf('=') + 1) : value);
        return response.getBody();
    }

    private ResponseEntity<String> callback(String path, String state) {
        HttpHeaders headers = new HttpHeaders();
        String cookie = bindingCookieByState.get(state);
        if (cookie != null) headers.add(HttpHeaders.COOKIE, cookie);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<Map<String, Object>> register(String name, String provider) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("transport", "STREAMABLE_HTTP");
        body.put("endpoint", "http://localhost:" + port + "/mcp");
        body.put("authType", "OAUTH");
        body.put("oauthProvider", provider);
        body.put("enabled", true);
        return http.exchange("/api/tools", HttpMethod.POST, new HttpEntity<>(body), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path) {
        return http.exchange(path, HttpMethod.POST, null, MAP);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static String sha256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** Records the Authorization header the in-process MCP server receives. */
    @TestConfiguration
    static class FakeProvider {

        @Bean
        FilterRegistrationBean<Filter> recordMcpAuthorization() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                String header = ((HttpServletRequest) request).getHeader("Authorization");
                if (header != null) {
                    LAST_MCP_AUTHORIZATION.set(header);
                }
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/mcp", "/mcp/*");
            return registration;
        }
    }

    /** TEST-ONLY fake OAuth token endpoint (standalone stub HTTP server). */
    static final com.sun.net.httpserver.HttpServer TOKEN_SERVER = startTokenServer();

    @org.springframework.test.context.DynamicPropertySource
    static void tokenUrl(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("connectai.oauth.providers.fakegh.token-url",
                () -> "http://127.0.0.1:" + TOKEN_SERVER.getAddress().getPort() + "/token");
        registry.add("connectai.oauth.providers.google.token-url",
                () -> "http://127.0.0.1:" + TOKEN_SERVER.getAddress().getPort() + "/token");
        registry.add("connectai.oauth.providers.slack.token-url",
                () -> "http://127.0.0.1:" + TOKEN_SERVER.getAddress().getPort() + "/token");
        registry.add("connectai.oauth.providers.grafana.token-url",
                () -> "http://127.0.0.1:" + TOKEN_SERVER.getAddress().getPort() + "/token");
        registry.add("connectai.oauth.providers.fakebasic.token-url",
                () -> "http://127.0.0.1:" + TOKEN_SERVER.getAddress().getPort() + "/token");
        registry.add("connectai.oauth.providers.fakegh.revocation-url",
                () -> "http://127.0.0.1:" + TOKEN_SERVER.getAddress().getPort() + "/revoke");
    }

    private static com.sun.net.httpserver.HttpServer startTokenServer() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/revoke", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_REVOKED_TOKEN_VALID.set(body.contains("token=gho_refresh") || body.contains("token=gho_first_token"));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.createContext("/token", exchange -> {
                LAST_TOKEN_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> form = new HashMap<>();
                for (String pair : body.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        form.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                    }
                }
                LAST_TOKEN_REDIRECT_URI.set(form.get("redirect_uri"));
                String json;
                try {
                    json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                            respond(form, LAST_TOKEN_AUTHORIZATION.get()));
                } catch (Exception e) {
                    json = "{\"error\":\"server_error\"}";
                }
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); // GitHub style: errors are HTTP 200 too
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> respond(Map<String, String> form, String authorization) throws Exception {
        boolean validBasic = false;
        boolean validGrafanaBasic = false;
        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] credentials = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8).split(":", 2);
            validBasic = credentials.length == 2
                    && "test:client".equals(decode(credentials[0]))
                    && "test+secret".equals(decode(credentials[1]));
            validGrafanaBasic = credentials.length == 2
                    && "grafana-test-client".equals(decode(credentials[0]))
                    && "grafana-test-secret".equals(decode(credentials[1]));
        }
        boolean validPost = "test-client".equals(form.get("client_id"))
                && "test-secret".equals(form.get("client_secret"));
        boolean validSlack = "slack-test-client".equals(form.get("client_id"))
                && "slack-test-secret".equals(form.get("client_secret"));
        if (!validBasic && !validGrafanaBasic && !validPost && !validSlack) {
            return Map.of("error", "incorrect_client_credentials");
        }
        if ("refresh_token".equals(form.get("grant_type"))) {
            if (FAIL_REFRESH.get()) return Map.of("error", "invalid_grant", "error_description", "secret-value");
            if (ROTATING_REFRESH_TOKENS.get()) {
                String accessToken = "google_access_" + ROTATING_REFRESH_COUNT.incrementAndGet();
                LAST_ISSUED_ACCESS_TOKEN.set(accessToken);
                return Map.of("access_token", accessToken, "token_type", "bearer", "expires_in", 3600);
            }
            return "gho_refresh".equals(form.get("refresh_token"))
                    ? Map.of("access_token", "gho_refreshed_token", "token_type", "bearer", "expires_in", 3600)
                    : Map.of("error", "bad_refresh_token");
        }
        String expected = CODE_CHALLENGES.get(form.get("code"));
        if (expected == null || form.get("code_verifier") == null || !expected.equals(sha256(form.get("code_verifier")))) {
            return Map.of("error", "bad_verification_code", "error_description", "secret-value");
        }
        CODE_CHALLENGES.remove(form.get("code"));
        if ("slack-error-code".equals(form.get("code"))) {
            return Map.of("ok", false, "error", "invalid_code");
        }
        if (validSlack) {
            return Map.of("ok", true, "access_token", "xoxp_fake_slack_user_token", "token_type", SLACK_TOKEN_TYPE.get(),
                    "team", Map.of("id", "T_FAKE"));
        }
        String expiresIn = NEXT_EXPIRES_IN.get();
        if (expiresIn != null) {
            return Map.of("access_token", "gho_expiring_token", "token_type", "bearer",
                    "expires_in", Integer.parseInt(expiresIn), "refresh_token", "gho_refresh");
        }
        return Map.of("access_token", "gho_first_token", "token_type", "bearer", "scope", "repo,read:user");
    }
}
