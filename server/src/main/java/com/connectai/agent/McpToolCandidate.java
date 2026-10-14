package com.connectai.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral description of an MCP tool made available to the agent planner.
 *
 * The planner needs to know what a tool can do, but it must not know about
 * MCP SDK classes, JSON-RPC, HTTP, sessions, or transports.
 *
 * {@code readOnlyHint} is the server's MCP annotation (null when the server does not say).
 * {@code serverName} is the user's label for the connection ("GitHub - Work"); several
 * connections can expose identically named tools, so the planner shows it next to each tool.
 */
public record McpToolCandidate(
        UUID serverId,
        String toolName,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Boolean readOnlyHint,
        String serverName,
        String providerKey
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
        serverName = serverName == null ? "" : serverName;
        providerKey = providerKey == null ? "" : providerKey;
    }

    public McpToolCandidate(UUID serverId, String toolName, String title, String description,
                            Map<String, Object> inputSchema, Boolean readOnlyHint, String serverName) {
        this(serverId, toolName, title, description, inputSchema, readOnlyHint, serverName, "");
    }

    public McpToolCandidate(
            UUID serverId,
            String toolName,
            String title,
            String description,
            Map<String, Object> inputSchema,
            Boolean readOnlyHint) {
        this(serverId, toolName, title, description, inputSchema, readOnlyHint, null, "");
    }

    public McpToolCandidate(
            UUID serverId,
            String toolName,
            String title,
            String description,
            Map<String, Object> inputSchema) {
        this(serverId, toolName, title, description, inputSchema, null, null, "");
    }

    /**
     * Tool names are not globally unique across MCP servers.
     */
    public String plannerId() {
        return serverId + "::" + toolName;
    }
}
