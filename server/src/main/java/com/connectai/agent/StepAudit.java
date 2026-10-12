package com.connectai.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Produces the redacted, bounded form of tool arguments that is safe to persist and show.
 * Values of keys that look like credentials are replaced; everything else is kept so the
 * run stays auditable.
 */
public final class StepAudit {

    static final int MAX_ARGUMENTS_CHARS = 4000;

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(token|secret|password|passwd|authorization|api[-_]?key|credential|cookie).*");

    private StepAudit() {
    }

    public static String argumentsJson(Map<String, Object> arguments) {
        return truncate(ToolCallKey.canonicalJson(redact(arguments)), MAX_ARGUMENTS_CHARS);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> redact(Map<String, Object> arguments) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (arguments == null) {
            return out;
        }
        arguments.forEach((key, value) -> {
            if (key != null && SENSITIVE_KEY.matcher(key).matches()) {
                out.put(key, "***");
            } else if (value instanceof Map<?, ?> nested) {
                out.put(key, redact((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                out.put(key, list.stream()
                        .map(item -> item instanceof Map<?, ?> m ? redact((Map<String, Object>) m) : item)
                        .toList());
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}
