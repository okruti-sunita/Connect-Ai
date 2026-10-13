package com.connectai.agent;

import java.util.regex.Pattern;

/** Redacts common secret-bearing diagnostic fragments before persistence or logging. */
public final class SafeDiagnostic {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?\\b(?:access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization[_-]?code|code|state|token|secret|password|passwd|api[_-]?key|authorization|cookie)\\b[\\\"']?\\s*(?:=|:)\\s*[\\\"']?)([^\\\"'\\s&,;\\]}]+)([\\\"']?)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern URL_USER_INFO = Pattern.compile("(https?://)[^/@\\s]+:[^/@\\s]+@");

    private SafeDiagnostic() {
    }

    public static String message(Throwable throwable, int maxLength) {
        String raw = throwable == null ? null : throwable.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = throwable == null ? "Unknown error" : throwable.getClass().getSimpleName();
        }
        return sanitize(raw, maxLength);
    }

    public static String sanitize(String value, int maxLength) {
        if (maxLength < 4) {
            throw new IllegalArgumentException("maxLength must be at least 4");
        }
        String safe = value == null ? "Unknown error" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1[REDACTED]$3");
        safe = BEARER.matcher(safe).replaceAll("$1[REDACTED]");
        safe = URL_USER_INFO.matcher(safe).replaceAll("$1[REDACTED]@");
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 3) + "...";
    }
}
