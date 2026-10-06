package com.connectai.agent;

/** Transport-neutral representation of one tool result content item. */
public record ToolExecutionContent(
        String type,
        String text
) {
}
