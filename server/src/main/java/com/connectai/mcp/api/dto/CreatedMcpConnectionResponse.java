package com.connectai.mcp.api.dto;

import com.connectai.mcp.model.McpServer;

import java.util.List;

public record CreatedMcpConnectionResponse(McpServer connection, int toolCount, List<String> toolNames) { }
