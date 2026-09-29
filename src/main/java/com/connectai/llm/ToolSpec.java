package com.connectai.llm;

import java.util.Map;

/** Describes a function the model is forced to "call", so its answer comes back as structured JSON. */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
