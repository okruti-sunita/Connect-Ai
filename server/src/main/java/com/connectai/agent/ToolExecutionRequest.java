package com.connectai.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Application-level request for executing an externally provided tool.
 *
 * The agent layer deliberately does not know about MCP, JSON-RPC, HTTP,
 * sessions, or the MCP SDK. Those concerns belong to an adapter.
 */
public record ToolExecutionRequest(
        UUID serverId,
        String toolName,
        Map<String, Object> arguments
) {

    public ToolExecutionRequest {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(toolName, "toolName");

        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }

        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
