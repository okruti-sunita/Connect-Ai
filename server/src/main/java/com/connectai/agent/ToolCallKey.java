package com.connectai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;
import java.util.UUID;

/**
 * Stable identity of one tool call: owning server, tool name and canonical (key-sorted)
 * arguments. Used to stop the agent repeating an identical call without blocking calls
 * whose arguments differ.
 */
public final class ToolCallKey {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ToolCallKey() {
    }

    public static String of(UUID serverId, String toolName, Map<String, Object> arguments) {
        return serverId + "::" + toolName + "::" + canonicalJson(arguments);
    }

    public static String of(ToolSelectionPlan plan) {
        return of(plan.serverId(), plan.toolName(), plan.arguments());
    }

    /** Arguments that only reorder or reformat results; a call differing only in these returns the same data. */
    private static final java.util.Set<String> ORDERING_ARGUMENTS = java.util.Set.of(
            "sort", "order", "direction", "minimal_output", "minimaloutput");

    /**
     * Identity of the data a call returns: like {@link #of(ToolSelectionPlan)} but ignoring
     * ordering-only arguments, so "search X sorted by joined" repeats "search X".
     */
    public static String dataKey(ToolSelectionPlan plan) {
        Map<String, Object> relevant = new java.util.LinkedHashMap<>(plan.arguments());
        relevant.keySet().removeIf(key -> ORDERING_ARGUMENTS.contains(key.toLowerCase(java.util.Locale.ROOT)));
        return of(plan.serverId(), plan.toolName(), relevant);
    }

    public static String canonicalJson(Map<String, Object> arguments) {
        try {
            return CANONICAL.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException e) {
            return String.valueOf(arguments);
        }
    }
}
