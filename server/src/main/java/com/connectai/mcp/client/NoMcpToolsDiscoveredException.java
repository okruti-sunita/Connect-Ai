package com.connectai.mcp.client;

/** An initialized MCP endpoint that exposes no tools cannot serve investigations in Connect AI. */
public final class NoMcpToolsDiscoveredException extends IllegalStateException {

    public static final String MESSAGE = "The MCP server initialized but returned no tools. "
            + "Check the endpoint and permissions, and confirm that its MCP server exposes tools.";

    public NoMcpToolsDiscoveredException() {
        super(MESSAGE);
    }
}
