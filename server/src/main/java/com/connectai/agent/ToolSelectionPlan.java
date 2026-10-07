package com.connectai.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Validated decision produced by the dynamic tool planner.
 *
 * This is a planning object, not an execution object.
 *
 * The planner decides:
 * - which MCP server should be used
 * - which tool should be used
 * - which arguments should be passed
 * - why the tool was selected
 *
 * Execution is deliberately handled later through ToolExecutionRequest.
 */
public record ToolSelectionPlan(
        UUID serverId,
        String toolName,
        Map<String, Object> arguments,
        String reason
) {

    public ToolSelectionPlan {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(toolName, "toolName");

        if (toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "toolName must not be blank");
        }

        /*
         * Defensive copy.
         *
         * The planner should not be affected if the caller modifies
         * the original arguments map after creating this plan.
         *
         * We also expose an unmodifiable map so callers cannot mutate
         * the plan through arguments().
         */
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new LinkedHashMap<>(arguments));

        /*
         * Reason is explanatory metadata.
         *
         * A missing reason should not make an otherwise valid plan fail.
         */
        reason = reason == null
                ? ""
                : reason;
    }

    /**
     * Converts the planning decision into the application-level
     * execution request.
     *
     * The planner does not execute the tool itself.
     *
     * The resulting request is later passed to:
     *
     * ToolExecutionPort
     *        |
     *        v
     * MCP adapter
     *        |
     *        v
     * MCP execution service
     */
    public ToolExecutionRequest toExecutionRequest() {
        return new ToolExecutionRequest(
                serverId,
                toolName,
                arguments);
    }
}