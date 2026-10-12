package com.connectai.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a tool may be offered to the investigation agent.
 *
 * Investigations only gather evidence, so by default only read-only tools are offered.
 * The server's MCP {@code readOnlyHint} annotation is authoritative when present; without
 * it, tools whose names start with a mutating verb are treated as writes (fail safe).
 */
public final class ToolSafetyPolicy {

    private static final Pattern MUTATING_NAME = Pattern.compile(
            "^(create|add|update|edit|delete|remove|merge|push|post|send|write|close|reopen|assign|fork|"
                    + "comment|request|run|trigger|cancel|rerun|dismiss|approve|submit|set|enable|disable|"
                    + "lock|unlock|transfer|archive|star|unstar|follow|unfollow|mark|resolve|upload|move|rename)"
                    + "([_\\-].*|[A-Z].*|)$");

    private static final Pattern MUTATING_SUFFIX = Pattern.compile(".*[_\\-](write|create|update|delete)$");

    private ToolSafetyPolicy() {
    }

    public static boolean isReadOnly(McpToolCandidate tool) {
        if (tool.readOnlyHint() != null) {
            return tool.readOnlyHint();
        }
        String name = tool.toolName();
        String lower = name.toLowerCase(Locale.ROOT);
        return !MUTATING_NAME.matcher(name).matches()
                && !MUTATING_NAME.matcher(lower).matches()
                && !MUTATING_SUFFIX.matcher(lower).matches();
    }
}
