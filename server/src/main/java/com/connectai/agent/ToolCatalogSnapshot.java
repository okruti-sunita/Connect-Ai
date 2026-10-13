package com.connectai.agent;

import java.util.List;
import java.util.Objects;

/** Current per-investigation MCP candidates and non-secret discovery warnings. */
public record ToolCatalogSnapshot(List<McpToolCandidate> tools, List<String> warnings) {
    public ToolCatalogSnapshot {
        Objects.requireNonNull(tools, "tools");
        tools = List.copyOf(tools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
