package com.connectai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Test double: returns whatever the test scripted and records what it was asked. */
class ScriptedLlm implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    JsonNode toolResult;
    String textResult;
    RuntimeException failure;

    int calls;
    String lastUser;
    ToolSpec lastTool;

    static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    ScriptedLlm returnsTool(String rawJson) {
        this.toolResult = json(rawJson);
        return this;
    }

    ScriptedLlm returnsText(String text) {
        this.textResult = text;
        return this;
    }

    ScriptedLlm fails() {
        this.failure = new LlmException("boom");
        return this;
    }

    @Override
    public JsonNode callTool(String system, String user, ToolSpec tool) {
        calls++;
        lastUser = user;
        lastTool = tool;
        if (failure != null) {
            throw failure;
        }
        return toolResult;
    }

    @Override
    public String complete(String system, String user) {
        calls++;
        lastUser = user;
        if (failure != null) {
            throw failure;
        }
        return textResult;
    }
}
