package com.connectai.mcp.client;

import java.net.URI;

/**
 * Splits a registered MCP endpoint into the origin used by the transport
 * builder and the MCP HTTP endpoint path.
 */
public record McpEndpoint(String baseUrl, String path) {

    public static McpEndpoint parse(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid MCP endpoint: " + endpoint, exception);
        }

        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("MCP endpoint must use http or https: " + endpoint);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("MCP endpoint must contain a host: " + endpoint);
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("MCP endpoint must not contain user information");
        }

        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint must not contain query parameters or fragments");
        }

        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }

        return new McpEndpoint(baseUrl, path);
    }
}
