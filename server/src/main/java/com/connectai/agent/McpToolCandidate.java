package com.connectai.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral description of an MCP tool made available to the agent planner.
 *
 * The planner needs to know what a tool can do, but it must not know about
 * MCP SDK classes, JSON-RPC, HTTP, sessions, or transports.
 */
public record McpToolCandidate(
        UUID serverId,
        String toolName,
        String title,
        String description,
        Map<String, Object> inputSchema
) {

    public McpToolCandidate {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(toolName, "toolName");

        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }

        title = title == null ? "" : title;
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    /**
     * Tool names are not globally unique across MCP servers.
     */
    public String plannerId() {
        return serverId + "::" + toolName;
    }
}
