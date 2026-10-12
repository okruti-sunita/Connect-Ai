package com.connectai.mcp.model;

public enum McpAuthType {
    NONE,
    /** A token pasted by the user. */
    BEARER,
    /** A token obtained by signing in with an OAuth provider (see connectai.oauth). */
    OAUTH
}
