package com.connectai.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Prevents planner-generated quotes from turning an ordinary Slack concept into an exact phrase.
 * Exact phrases explicitly quoted by the user are left untouched. This only normalizes arguments
 * for a schema-discovered Slack search capability; it does not assume an operation ID or syntax.
 */
public final class SlackSearchQueryPolicy {
    private SlackSearchQueryPolicy() { }

    public static Map<String, Object> normalize(String question, McpToolCandidate tool,
                                                 Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (question == null || tool == null || !"slack".equalsIgnoreCase(tool.providerKey())
                || !supportsMessageSearch(tool)) return normalized;
        // Explicitly quoted user text has a meaningful exact-match intent; preserve the model's
        // query as given rather than attempting to reinterpret provider-specific syntax.
        if (question.matches("(?s).*(?:\"[^\"]+\"|“[^”]+”|'[^']+').*")) return normalized;

        for (Map.Entry<String, Object> entry : Map.copyOf(normalized).entrySet()) {
            String key = compact(entry.getKey());
            if (key.equals("naturallanguagequery") || key.equals("query") || key.equals("searchquery")
                    || key.equals("keywords") || key.equals("terms")) {
                normalized.put(entry.getKey(), removeModelQuotes(entry.getValue()));
            }
        }
        return normalized;
    }

    private static boolean supportsMessageSearch(McpToolCandidate tool) {
        Object properties = tool.inputSchema().get("properties");
        if (!(properties instanceof Map<?, ?> map)) return false;
        boolean searchField = map.keySet().stream().map(String::valueOf).map(SlackSearchQueryPolicy::compact)
                .anyMatch(key -> key.equals("keywords") || key.equals("naturallanguagequery")
                        || key.equals("searchquery") || key.equals("query"));
        String metadata = (tool.toolName() + " " + tool.title() + " " + tool.description()).toLowerCase(Locale.ROOT);
        return searchField && (metadata.contains("message") || metadata.contains("search"));
    }

    private static Object removeModelQuotes(Object value) {
        if (value instanceof String text) {
            return text.replaceAll("(?<!\\w)[\"“]([^\"”]+)[\"”](?!\\w)", "$1")
                    .replaceAll("(?<!\\w)'([^']+)'(?!\\w)", "$1");
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(item -> item instanceof String ? removeModelQuotes(item) : item).toList();
        }
        return value;
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
