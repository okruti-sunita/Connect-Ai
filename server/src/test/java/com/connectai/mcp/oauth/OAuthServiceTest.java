package com.connectai.mcp.oauth;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthServiceTest {

    @Test
    void callbackWithoutStateDoesNotCompleteThePendingAttempt() {
        Fixture fixture = new Fixture(600);
        OAuthService.Started started = fixture.start();

        OAuthService.Attempt callback = fixture.service.complete(null, "some-code", null, null, started.cookieValue());

        assertThat(callback.status()).isEqualTo(OAuthService.AttemptStatus.FAILED);
        assertThat(fixture.service.attempt(started.attemptId()).status()).isEqualTo(OAuthService.AttemptStatus.PENDING);
    }

    @Test
    void mismatchedBrowserBindingDoesNotConsumeTheOAuthState() {
        Fixture fixture = new Fixture(600);
        OAuthService.Started started = fixture.start();
        String state = state(started.authorizationUrl());

        OAuthService.Attempt callback = fixture.service.complete(state, "some-code", null, null, "wrong-browser-cookie");

        assertThat(callback.status()).isEqualTo(OAuthService.AttemptStatus.FAILED);
        assertThat(fixture.service.attempt(started.attemptId()).status()).isEqualTo(OAuthService.AttemptStatus.PENDING);
    }

    @Test
    void closingPopupCancelsOnlyTheUncompletedAuthorizationAttempt() {
        Fixture fixture = new Fixture(600);
        OAuthService.Started started = fixture.start();

        OAuthService.Attempt cancelled = fixture.service.cancel(started.attemptId());

        assertThat(cancelled.status()).isEqualTo(OAuthService.AttemptStatus.FAILED);
        assertThat(cancelled.message()).contains("window was closed");
        assertThat(fixture.service.attempt(started.attemptId())).isEqualTo(cancelled);
    }

    @Test
    void expiredOAuthAttemptCannotBeCompletedOrPolled() throws InterruptedException {
        Fixture fixture = new Fixture(1);
        OAuthService.Started started = fixture.start();
        String state = state(started.authorizationUrl());
        Thread.sleep(1_100);

        OAuthService.Attempt callback = fixture.service.complete(state, "some-code", null, null, started.cookieValue());

        assertThat(callback.status()).isEqualTo(OAuthService.AttemptStatus.FAILED);
        assertThat(callback.message()).contains("invalid or has expired");
        assertThatThrownBy(() -> fixture.service.attempt(started.attemptId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void providerMetadataDoesNotAdvertiseMalformedOAuthEndpointsAsConfigured() {
        OAuthProperties properties = new OAuthProperties();
        OAuthProperties.Provider provider = new OAuthProperties.Provider();
        provider.setAuthorizeUrl("http://provider.example/authorize");
        provider.setTokenUrl("https://provider.example/token");
        provider.setClientId("configured-client-id");
        provider.setClientSecret("configured-client-secret");
        properties.getProviders().put("invalid", provider);
        OAuthService service = new OAuthService(properties, mock(McpServerRepository.class),
                new SecretEncryptionService("test-only-encryption-key-long-enough-123456"), new ObjectMapper());

        OAuthService.ProviderInfo info = service.providers().getFirst();

        assertThat(info.configured()).isFalse();
        assertThat(info.configurationIssue()).contains("Use HTTPS").doesNotContain("configured-client-secret");
    }

    @Test
    void grafanaOAuthRequiresTheRegisteredClientScopes() {
        OAuthProperties properties = new OAuthProperties();
        OAuthProperties.Provider provider = new OAuthProperties.Provider();
        provider.setAuthorizeUrl("https://grafana.example/authorize");
        provider.setTokenUrl("https://grafana.example/token");
        provider.setClientId("configured-client-id");
        provider.setClientSecret("configured-client-secret");
        properties.getProviders().put("grafana", provider);
        OAuthService service = new OAuthService(properties, mock(McpServerRepository.class),
                new SecretEncryptionService("test-only-encryption-key-long-enough-123456"), new ObjectMapper());

        OAuthService.ProviderInfo info = service.providers().getFirst();

        assertThat(info.configured()).isFalse();
        assertThat(info.configurationIssue()).contains("Grafana OAuth scopes are not configured", "GRAFANA_OAUTH_SCOPES")
                .doesNotContain("configured-client-secret");
    }

    @Test
    void grafanaConfigurationErrorNamesMissingSettingsWithoutExposingValues() {
        OAuthProperties properties = new OAuthProperties();
        OAuthProperties.Provider provider = new OAuthProperties.Provider();
        provider.setAuthorizeUrl("https://grafana.example/authorize");
        provider.setClientId("configured-client-id");
        provider.setClientSecret("configured-client-secret");
        properties.getProviders().put("grafana", provider);
        OAuthService service = new OAuthService(properties, mock(McpServerRepository.class),
                new SecretEncryptionService("test-only-encryption-key-long-enough-123456"), new ObjectMapper());

        assertThatThrownBy(() -> service.requireConfigured("grafana"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GRAFANA_OAUTH_TOKEN_URL")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("configured-client-secret"));
    }

    private static String state(String authorizationUrl) {
        return java.net.URI.create(authorizationUrl).getRawQuery().lines()
                .flatMap(query -> java.util.Arrays.stream(query.split("&")))
                .filter(part -> part.startsWith("state="))
                .map(part -> java.net.URLDecoder.decode(part.substring("state=".length()), java.nio.charset.StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private static final class Fixture {
        private final UUID serverId = UUID.randomUUID();
        private final OAuthService service;

        private Fixture(long ttlSeconds) {
            OAuthProperties properties = new OAuthProperties();
            properties.setRedirectUri("http://localhost/callback");
            properties.setAttemptTtlSeconds(ttlSeconds);
            OAuthProperties.Provider provider = new OAuthProperties.Provider();
            provider.setDisplayName("Test Provider");
            provider.setAuthorizeUrl("https://provider.example/authorize");
            provider.setTokenUrl("https://provider.example/token");
            provider.setClientId("test-client");
            provider.setClientSecret("test-secret");
            provider.setScopes("read-only");
            properties.getProviders().put("test", provider);

            EMcpServer server = new EMcpServer("Test", McpTransportType.STREAMABLE_HTTP,
                    "https://mcp.example/mcp", McpAuthType.OAUTH, null, true);
            server.useOAuthProvider("test");
            McpServerRepository repository = mock(McpServerRepository.class);
            when(repository.findById(serverId)).thenReturn(Optional.of(server));
            service = new OAuthService(properties, repository,
                    new SecretEncryptionService("test-only-encryption-key-long-enough-123456"), new ObjectMapper());
        }

        private OAuthService.Started start() {
            return service.start(serverId);
        }
    }
}
