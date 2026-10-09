package com.connectai.agent;

import java.util.List;

/** Application-facing catalog of tools currently available to the agent. */
public interface AgentToolCatalog {
    List<McpToolCandidate> getAvailableTools();
}
