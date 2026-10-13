package com.connectai.mcp.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;
import java.net.URI;

/**
 * OAuth sign-in endpoints for MCP connections.
 *
 * GET  /api/oauth/providers                 which providers exist and are configured
 * POST /api/tools/{id}/oauth/start          begin sign-in for one connection -> {attemptId, authorizationUrl}
 * GET  /api/oauth/attempts/{attemptId}      PENDING / SUCCEEDED / FAILED (polled by the UI)
 * GET  /api/oauth/callback                  the provider's redirect target (shows a small page)
 */
@RestController
public class OAuthResource {

    public record StartResponse(String attemptId, String authorizationUrl, String callbackOrigin) { }

    private final OAuthService oauth;
    private final OAuthProperties properties;

    public OAuthResource(OAuthService oauth, OAuthProperties properties) {
        this.oauth = oauth;
        this.properties = properties;
    }

    @GetMapping("/api/oauth/providers")
    public List<OAuthService.ProviderInfo> providers() {
        return oauth.providers();
    }

    @PostMapping("/api/tools/{id}/oauth/start")
    public ResponseEntity<StartResponse> start(@PathVariable UUID id, HttpServletRequest request) {
        String expectedCallbackPath = request.getContextPath() + "/api/oauth/callback";
        if (!expectedCallbackPath.equals(URI.create(properties.getRedirectUri()).getPath())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONNECTAI_OAUTH_REDIRECT_URI path must match this deployment's /api/oauth/callback route (" + expectedCallbackPath + ").");
        }
        OAuthService.Started started = oauth.start(id);
        String cookiePath = request.getContextPath() + "/api/oauth/callback";
        ResponseCookie binding = ResponseCookie.from(started.cookieName(), started.cookieValue())
                .httpOnly(true)
                .secure(request.isSecure() || properties.getRedirectUri().startsWith("https://"))
                .sameSite(request.isSecure() || properties.getRedirectUri().startsWith("https://") ? "None" : "Lax")
                .path(cookiePath)
                .maxAge(properties.getAttemptTtlSeconds())
                .build();
        String callbackOrigin = URI.create(properties.getRedirectUri()).resolve("/").toString();
        if (callbackOrigin.endsWith("/")) callbackOrigin = callbackOrigin.substring(0, callbackOrigin.length() - 1);
        return ResponseEntity.ok().header("Set-Cookie", binding.toString())
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(new StartResponse(started.attemptId(), started.authorizationUrl(), callbackOrigin));
    }

    @GetMapping("/api/oauth/attempts/{attemptId}")
    public OAuthService.Attempt attempt(@PathVariable String attemptId) {
        return oauth.attempt(attemptId);
    }

    @GetMapping(value = "/api/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            HttpServletRequest request) {

        String bindingValue = null;
        if (state != null && request.getCookies() != null) {
            String cookieName = "connectai_oauth_" + state;
            for (Cookie cookie : request.getCookies()) {
                if (cookieName.equals(cookie.getName())) {
                    bindingValue = cookie.getValue();
                    break;
                }
            }
        }
        OAuthService.Attempt attempt = oauth.complete(state, code, error, errorDescription, bindingValue);
        boolean ok = attempt.status() == OAuthService.AttemptStatus.SUCCEEDED;
        String message = HtmlUtils.htmlEscape(attempt.message());
        String attemptId = HtmlUtils.htmlEscape(attempt.id() == null ? "" : attempt.id());
        String status = HtmlUtils.htmlEscape(attempt.status().name());
        String frontendOrigin = HtmlUtils.htmlEscape(validatedFrontendOrigin());
        String page = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Connect AI sign-in</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:flex;
                       align-items:center;justify-content:center;min-height:100vh;margin:0}
                  main{max-width:420px;padding:24px;border-radius:12px;background:#1e293b;text-align:center}
                  h1{font-size:18px;margin:0 0 8px} p{margin:0;color:#cbd5e1}
                </style></head>
                <body><main data-attempt-id="%s" data-status="%s" data-target-origin="%s"><h1>%s</h1><p>%s</p></main>
                <script>(function(){var m=document.querySelector('main');if(window.opener){window.opener.postMessage({type:'connect-ai-oauth',attemptId:m.dataset.attemptId,status:m.dataset.status},m.dataset.targetOrigin);}%s})();</script></body></html>
                """.formatted(
                attemptId,
                status,
                frontendOrigin,
                ok ? "Access granted" : "Sign-in failed",
                message,
                ok ? "setTimeout(function(){window.close();},1200);" : "");
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer");
        if (state != null && state.matches("[A-Za-z0-9_-]{20,100}")
                && !attempt.message().startsWith("This sign-in did not originate from this browser.")) {
            String cookieName = "connectai_oauth_" + state;
            String cookiePath = request.getContextPath() + "/api/oauth/callback";
            ResponseCookie clear = ResponseCookie.from(cookieName, "")
                    .httpOnly(true).secure(request.isSecure() || properties.getRedirectUri().startsWith("https://"))
                    .sameSite(request.isSecure() || properties.getRedirectUri().startsWith("https://") ? "None" : "Lax")
                    .path(cookiePath).maxAge(0).build();
            response.header("Set-Cookie", clear.toString());
        }
        return response.body(page);
    }

    private String validatedFrontendOrigin() {
        try {
            URI uri = URI.create(properties.getFrontendOrigin());
            String scheme = uri.getScheme();
            boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || !("https".equalsIgnoreCase(scheme) || loopbackHttp)) {
                throw new IllegalArgumentException();
            }
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + uri.getRawAuthority();
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONNECTAI_FRONTEND_ORIGIN must be an HTTPS browser origin (HTTP is allowed only for localhost development).");
        }
    }

    @Configuration
    @EnableConfigurationProperties(OAuthProperties.class)
    static class OAuthConfig {
    }
}
