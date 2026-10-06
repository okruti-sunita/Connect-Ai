package com.connectai.agent;

/**
 * Application boundary used by the agent when it needs to execute an
 * externally provided tool.
 *
 * Implementations may use MCP today and another protocol in the future.
 */
public interface ToolExecutionPort {

    ToolExecutionResult execute(ToolExecutionRequest request);
}
