package com.connectai.agent;

import java.util.List;

/** Adds semantically relevant tools to the live lexical candidate set when configured. */
public interface ToolSemanticRetriever {

    List<McpToolCandidate> retrieve(String question, List<McpToolCandidate> liveTools, int limit);

    static ToolSemanticRetriever disabled() {
        return (question, tools, limit) -> List.of();
    }
}
