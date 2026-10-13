package com.connectai.mcp.api.dto;

import java.util.List;

public record McpConnectionTestResponse(boolean success, List<Step> steps, int toolCount,
                                        List<String> toolNames, String code, String message, String hint) {
    public record Step(String name, boolean passed, String code, String message, String hint) { }
}
