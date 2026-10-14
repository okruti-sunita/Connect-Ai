package com.connectai.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves an explicitly named connected provider and scopes tools before planning or fan-out. */
public final class ProviderConstraint {

    private static final Set<String> NON_PROVIDER_IDENTIFIERS = Set.of(
            "account", "login", "user", "server", "tool", "connection", "provider", "data", "mcp", "custom");

    /** Display names for the first-party catalog keys; custom MCPs use their connection label. */
    private static final Map<String, String> CATALOG_NAMES = Map.ofEntries(
            Map.entry("github", "github"), Map.entry("linear", "linear"), Map.entry("jira", "jira"),
            Map.entry("atlassian", "atlassian"), Map.entry("slack", "slack"), Map.entry("notion", "notion"),
            Map.entry("splunk", "splunk"), Map.entry("pagerduty", "pagerduty"), Map.entry("datadog", "datadog"),
            Map.entry("grafana", "grafana"), Map.entry("google-drive", "google drive"), Map.entry("custom", "custom"));

    public record Scope(boolean constrained, List<McpToolCandidate> tools, Set<String> providerKeys) {
        public Scope {
            tools = List.copyOf(tools);
            providerKeys = Set.copyOf(providerKeys);
        }
    }

    private ProviderConstraint() { }

    public static Scope apply(String question, List<McpToolCandidate> tools) {
        if (question == null || question.isBlank() || tools == null || tools.isEmpty()) {
            return new Scope(false, tools == null ? List.of() : tools, Set.of());
        }
        String normalizedQuestion = normalize(question);
        Set<String> mentioned = new LinkedHashSet<>();
        for (Map.Entry<String, String> provider : CATALOG_NAMES.entrySet()) {
            if (containsPhrase(normalizedQuestion, normalize(provider.getValue()))) mentioned.add(provider.getKey());
        }
        for (McpToolCandidate tool : tools) {
            String key = identity(tool);
            if (!key.isBlank() && containsPhrase(normalizedQuestion, key)) mentioned.add(key);
        }
        if (mentioned.isEmpty()) return new Scope(false, tools, Set.of());
        List<McpToolCandidate> scoped = new ArrayList<>();
        for (McpToolCandidate tool : tools) {
            if (mentioned.contains(identity(tool))) scoped.add(tool);
        }
        return new Scope(true, scoped, mentioned);
    }

    private static String identity(McpToolCandidate tool) {
        String providerKey = normalize(tool.providerKey());
        if (!providerKey.isBlank() && CATALOG_NAMES.containsKey(providerKey)) return providerKey;
        String serverName = tool.serverName().split("\\s+-\\s+", 2)[0];
        String normalizedName = normalize(serverName);
        String metadata = normalize(tool.serverName() + " " + tool.toolName() + " " + tool.title() + " " + tool.description());
        for (Map.Entry<String, String> provider : CATALOG_NAMES.entrySet()) {
            String displayName = normalize(provider.getValue());
            if (containsPhrase(normalizedName, displayName) || containsPhrase(metadata, displayName)) {
                return provider.getKey();
            }
        }
        if (!providerKey.isBlank() && !NON_PROVIDER_IDENTIFIERS.contains(providerKey)) return providerKey;
        return NON_PROVIDER_IDENTIFIERS.contains(normalizedName) ? "" : normalizedName;
    }

    private static boolean containsPhrase(String question, String phrase) {
        return !phrase.isBlank() && (" " + question + " ").contains(" " + phrase + " ");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }
}
