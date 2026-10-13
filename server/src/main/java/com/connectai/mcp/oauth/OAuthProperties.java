package com.connectai.mcp.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth 2.0 providers that MCP connections can sign in with (instead of pasting a token).
 *
 * <pre>
 * connectai.oauth:
 *   redirect-uri: http://localhost:8080/connect-ai/api/oauth/callback
 *   providers:
 *     github:
 *       display-name: GitHub
 *       authorize-url: https://github.com/login/oauth/authorize
 *       token-url: https://github.com/login/oauth/access_token
 *       client-id: ${GITHUB_OAUTH_CLIENT_ID:}
 *       client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:}
 *       scopes: repo read:org read:user
 * </pre>
 *
 * A provider without client id/secret is listed as not configured and cannot be used.
 */
@ConfigurationProperties(prefix = "connectai.oauth")
public class OAuthProperties {

    /** Where the provider sends the browser back; must match the OAuth app's callback URL exactly. */
    private String redirectUri = "http://localhost:8080/connect-ai/api/oauth/callback";

    /** Exact browser origin allowed to receive the completion message from the callback popup. */
    private String frontendOrigin = "http://localhost:5173";

    /** How long a started authorization may take before it is discarded. */
    private long attemptTtlSeconds = 600;

    private Map<String, Provider> providers = new LinkedHashMap<>();

    public static class Provider {
        private String displayName;
        private String authorizeUrl;
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scopes = "";
        private String accessType;
        private String clientAuthentication = "client_secret_post";
        private String revocationUrl;
        private Map<String, String> authorizationParameters = new LinkedHashMap<>();
        private Map<String, String> tokenParameters = new LinkedHashMap<>();
        private boolean pkce = true;

        public boolean isConfigured() {
            return notBlank(authorizeUrl) && notBlank(tokenUrl) && notBlank(clientId) && notBlank(clientSecret);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAuthorizeUrl() { return authorizeUrl; }
        public void setAuthorizeUrl(String authorizeUrl) { this.authorizeUrl = authorizeUrl; }
        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getScopes() { return scopes; }
        public void setScopes(String scopes) { this.scopes = scopes; }
        public String getAccessType() { return accessType; }
        public void setAccessType(String accessType) { this.accessType = accessType; }
        public String getClientAuthentication() { return clientAuthentication; }
        public void setClientAuthentication(String clientAuthentication) { this.clientAuthentication = clientAuthentication; }
        public String getRevocationUrl() { return revocationUrl; }
        public void setRevocationUrl(String revocationUrl) { this.revocationUrl = revocationUrl; }
        public Map<String, String> getAuthorizationParameters() { return authorizationParameters; }
        public void setAuthorizationParameters(Map<String, String> authorizationParameters) {
            this.authorizationParameters = authorizationParameters == null ? new LinkedHashMap<>() : authorizationParameters;
        }
        public Map<String, String> getTokenParameters() { return tokenParameters; }
        public void setTokenParameters(Map<String, String> tokenParameters) {
            this.tokenParameters = tokenParameters == null ? new LinkedHashMap<>() : tokenParameters;
        }
        public boolean isPkce() { return pkce; }
        public void setPkce(boolean pkce) { this.pkce = pkce; }
    }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getFrontendOrigin() { return frontendOrigin; }
    public void setFrontendOrigin(String frontendOrigin) { this.frontendOrigin = frontendOrigin; }
    public long getAttemptTtlSeconds() { return attemptTtlSeconds; }
    public void setAttemptTtlSeconds(long attemptTtlSeconds) { this.attemptTtlSeconds = attemptTtlSeconds; }
    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }
}
