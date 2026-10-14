package com.connectai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeDiagnosticTest {

    @Test
    void redactsCredentialAssignmentsBearerTokensAndUrlUserInfo() {
        String safe = SafeDiagnostic.sanitize(
                "HTTP failure: https://alice:pw@example.test/callback?access_token=abc&state=xyz "
                        + "and body {\"refresh_token\":\"rotated-value\"} "
                        + "Bearer eyJhbGciOi.secret",
                500);

        assertThat(safe).contains("https://[REDACTED]@example.test/callback?access_token=[REDACTED]&state=[REDACTED]");
        assertThat(safe).contains("Bearer [REDACTED]");
        assertThat(safe).doesNotContain("alice", "pw", "abc", "xyz", "rotated-value", "eyJhbGciOi.secret");
        assertThat(safe).contains("\"refresh_token\":\"[REDACTED]\"");
    }

    @Test
    void boundsSingleLineDiagnostic() {
        String safe = SafeDiagnostic.sanitize("failure\n" + "x".repeat(100), 24);

        assertThat(safe).hasSize(24).doesNotContain("\n");
    }

    @Test
    void redactsProviderRateLimitResponseBody() {
        String safe = SafeDiagnostic.sanitize(
                "Planner error: Groq HTTP 429: {\"organization\":\"org-private\",\"message\":\"try later\"}", 500);

        assertThat(safe).contains("LLM provider rate limit (HTTP 429)")
                .doesNotContain("org-private", "try later");
    }
}
