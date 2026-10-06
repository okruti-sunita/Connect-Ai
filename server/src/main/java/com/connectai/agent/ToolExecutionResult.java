package com.connectai.agent;

import java.util.List;

/**
 * Transport-neutral result returned to the agent layer after a tool call.
 */
public record ToolExecutionResult(
        String toolName,
        boolean error,
        List<ToolExecutionContent> content,
        Object structuredContent
) {

    public ToolExecutionResult {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
