package com.connectai.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facts about the run's context that the answer step needs to stay honest.
 *
 * @param uncoveredCapabilities kinds of data the question asks for that no connected tool provides
 * @param connectionNames       every connected MCP server (id to label), so the answer can tell
 *                              which connections were actually queried and which were not
 */
public record AnswerHints(List<String> uncoveredCapabilities, Map<UUID, String> connectionNames,
                          List<String> warnings) {

    public static final AnswerHints NONE = new AnswerHints(List.of(), Map.of(), List.of());

    public AnswerHints(List<String> uncoveredCapabilities, Map<UUID, String> connectionNames) {
        this(uncoveredCapabilities, connectionNames, List.of());
    }

    public AnswerHints {
        uncoveredCapabilities = uncoveredCapabilities == null ? List.of() : List.copyOf(uncoveredCapabilities);
        connectionNames = connectionNames == null ? Map.of() : Map.copyOf(connectionNames);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Connections that exist but contributed no successful step. Empty with fewer than two connections. */
    public List<String> unqueriedConnections(AgentExecutionState state) {
        if (connectionNames.size() < 2) {
            return List.of();
        }
        var used = state.steps().stream()
                .filter(step -> step.status() == AgentStepStatus.SUCCESS)
                .map(step -> step.plan().serverId())
                .toList();
        return connectionNames.entrySet().stream()
                .filter(entry -> !used.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted()
                .toList();
    }
}
