package com.connectai.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The only thing the rest of the app knows about the model provider.
 * Swapping Anthropic for another vendor means writing one new implementation of this interface.
 */
public interface LlmClient {

    /** Forces the model to call {@code tool} and returns the JSON arguments it produced. */
    JsonNode callTool(String system, String user, ToolSpec tool);

    /** Plain text completion. */
    String complete(String system, String user);
}
