package com.connectai.agent;

import java.util.List;

/** Application-facing catalog of tools currently available to the agent. */
public interface AgentToolCatalog {
    List<McpToolCandidate> getAvailableTools();

    /** Gets a fresh per-investigation catalog, optionally with safe discovery warnings. */
    default ToolCatalogSnapshot snapshot() {
        return new ToolCatalogSnapshot(getAvailableTools(), List.of());
    }
}
