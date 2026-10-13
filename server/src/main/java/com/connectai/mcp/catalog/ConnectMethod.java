package com.connectai.mcp.catalog;

/** Connection methods the metadata catalog can describe. A method is selectable only when implemented. */
public enum ConnectMethod {
    OAUTH_APP,
    OAUTH_DISCOVERY,
    TOKEN_BEARER,
    TOKEN_BASIC,
    TOKEN_HEADER,
    TOKEN_HEADERS,
    NONE
}
