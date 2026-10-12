package com.connectai.mcp;

import com.connectai.mcp.client.McpConnectionService;
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
                "connectai.oauth.redirect-uri=http://localhost/callback-under-test",
                "connectai.oauth.providers.fakegh.display-name=Fake GitHub",
                "connectai.oauth.providers.fakegh.authorize-url=https://provider.example/authorize",
                "connectai.oauth.providers.fakegh.client-id=test-client",
                "connectai.oauth.providers.fakegh.client-secret=test-secret",
                "connectai.oauth.providers.fakegh.scopes=repo read:user",
                "connectai.oauth.providers.unconfigured.authorize-url=https://other.example/authorize"
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

    private String serverId;

    @Test
    @Order(1)
    void providersShowWhichAreConfigured() {
        var providers = http.exchange("/api/oauth/providers", HttpMethod.GET, null,
                new ParameterizedTypeReference<java.util.List<Map<String, Object>>>() { }).getBody();
        assertThat(providers).anyMatch(p -> "fakegh".equals(p.get("id")) && Boolean.TRUE.equals(p.get("configured")));
        assertThat(providers).anyMatch(p -> "unconfigured".equals(p.get("id")) && Boolean.FALSE.equals(p.get("configured")));
    }

    @Test
    @Order(2)
    void oauthRegistrationIsValidated() {
        assertThat(register("oauth-unknown", "nope").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(register("oauth-unconfigured", "unconfigured").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

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
        Map<String, Object> started = post("/api/tools/" + serverId + "/oauth/start").getBody();
        String url = started.get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        assertThat(url).startsWith("https://provider.example/authorize?");
        assertThat(query).containsEntry("client_id", "test-client").containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256").containsKey("code_challenge").containsKey("state");
        assertThat(decode(query.get("redirect_uri"))).isEqualTo("http://localhost/callback-under-test");
        assertThat(decode(query.get("scope"))).isEqualTo("repo read:user");
        String state = query.get("state");
        CODE_CHALLENGES.put("good-code", query.get("code_challenge"));

        assertThat(attempt(state).get("status")).isEqualTo("PENDING");

        ResponseEntity<String> page = http.getForEntity("/api/oauth/callback?code=good-code&state=" + state, String.class);
        assertThat(page.getBody()).contains("Access granted");
        assertThat(attempt(state).get("status")).isEqualTo("SUCCEEDED");

        Map<String, Object> tool = http.exchange("/api/tools/" + serverId, HttpMethod.GET, null, MAP).getBody();
        assertThat(tool).containsEntry("secretConfigured", true);
        assertThat(http.getForEntity("/api/tools", String.class).getBody()).doesNotContain("gho_first_token");

        assertThat(post("/api/tools/" + serverId + "/connect").getBody()).containsEntry("status", "CONNECTED");
        assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer gho_first_token");

        // The state is single-use.
        assertThat(http.getForEntity("/api/oauth/callback?code=good-code&state=" + state, String.class).getBody())
                .contains("invalid or has expired");
        post("/api/tools/" + serverId + "/disconnect");
    }

    @Test
    @Order(4)
    void deniedAndRejectedSignInsFailHonestly() {
        String denied = startState();
        assertThat(http.getForEntity("/api/oauth/callback?error=access_denied&state=" + denied, String.class).getBody())
                .contains("Sign-in failed").contains("cancelled or access was denied");
        assertThat(attempt(denied)).containsEntry("status", "FAILED");

        String badCode = startState();
        http.getForEntity("/api/oauth/callback?code=wrong-code&state=" + badCode, String.class);
        assertThat(attempt(badCode).get("message").toString()).contains("bad_verification_code");

        // A failed re-sign-in keeps the earlier token.
        assertThat(servers.findById(UUID.fromString(serverId)).orElseThrow().hasCredential()).isTrue();
    }

    @Test
    @Order(5)
    void expiringTokensAreRefreshedBeforeConnecting() {
        NEXT_EXPIRES_IN.set("60"); // inside the refresh margin, with a refresh token
        String state = startState();
        CODE_CHALLENGES.put("good-code", challengeOf(state));
        http.getForEntity("/api/oauth/callback?code=good-code&state=" + state, String.class);
        assertThat(attempt(state)).containsEntry("status", "SUCCEEDED");
        NEXT_EXPIRES_IN.set(null);

        assertThat(post("/api/tools/" + serverId + "/connect").getBody()).containsEntry("status", "CONNECTED");
        assertThat(LAST_MCP_AUTHORIZATION.get()).isEqualTo("Bearer gho_refreshed_token");
        post("/api/tools/" + serverId + "/disconnect");
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

    private String startState() {
        String url = post("/api/tools/" + serverId + "/oauth/start").getBody().get("authorizationUrl").toString();
        Map<String, String> query = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        challengeByState.put(query.get("state"), query.get("code_challenge"));
        return query.get("state");
    }

    private String challengeOf(String state) {
        return challengeByState.get(state);
    }

    private Map<String, Object> attempt(String state) {
        return http.exchange("/api/oauth/attempts/" + state, HttpMethod.GET, null, MAP).getBody();
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
    }

    private static com.sun.net.httpserver.HttpServer startTokenServer() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> form = new HashMap<>();
                for (String pair : body.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        form.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                    }
                }
                String json;
                try {
                    json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(respond(form));
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

    private static Map<String, Object> respond(Map<String, String> form) throws Exception {
        if (!"test-client".equals(form.get("client_id")) || !"test-secret".equals(form.get("client_secret"))) {
            return Map.of("error", "incorrect_client_credentials");
        }
        if ("refresh_token".equals(form.get("grant_type"))) {
            return "gho_refresh".equals(form.get("refresh_token"))
                    ? Map.of("access_token", "gho_refreshed_token", "token_type", "bearer")
                    : Map.of("error", "bad_refresh_token");
        }
        String expected = CODE_CHALLENGES.get(form.get("code"));
        if (expected == null || form.get("code_verifier") == null || !expected.equals(sha256(form.get("code_verifier")))) {
            return Map.of("error", "bad_verification_code");
        }
        CODE_CHALLENGES.remove(form.get("code"));
        String expiresIn = NEXT_EXPIRES_IN.get();
        if (expiresIn != null) {
            return Map.of("access_token", "gho_expiring_token", "token_type", "bearer",
                    "expires_in", Integer.parseInt(expiresIn), "refresh_token", "gho_refresh");
        }
        return Map.of("access_token", "gho_first_token", "token_type", "bearer", "scope", "repo,read:user");
    }
}
