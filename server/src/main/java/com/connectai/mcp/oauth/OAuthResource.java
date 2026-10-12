package com.connectai.mcp.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.UUID;

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

    private final OAuthService oauth;

    public OAuthResource(OAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/api/oauth/providers")
    public List<OAuthService.ProviderInfo> providers() {
        return oauth.providers();
    }

    @PostMapping("/api/tools/{id}/oauth/start")
    public OAuthService.Started start(@PathVariable UUID id) {
        return oauth.start(id);
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
            @RequestParam(name = "error_description", required = false) String errorDescription) {

        OAuthService.Attempt attempt = oauth.complete(state, code, error, errorDescription);
        boolean ok = attempt.status() == OAuthService.AttemptStatus.SUCCEEDED;
        String message = HtmlUtils.htmlEscape(attempt.message());
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
                <body><main><h1>%s</h1><p>%s</p></main>
                <script>%s</script></body></html>
                """.formatted(
                ok ? "Access granted" : "Sign-in failed",
                message,
                ok ? "setTimeout(function(){window.close();},1200);" : "");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(page);
    }

    @Configuration
    @EnableConfigurationProperties(OAuthProperties.class)
    static class OAuthConfig {
    }
}
