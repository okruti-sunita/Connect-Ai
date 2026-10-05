package com.connectai.mcp.operation;

import com.connectai.mcp.api.dto.RegisterMcpServerRequest;
import com.connectai.mcp.model.McpServer;

import java.util.List;

public interface McpToolOperations {

    McpServer registerTool(RegisterMcpServerRequest request);

    List<McpServer> getTools();

    McpServer getTool(Long id);
}
