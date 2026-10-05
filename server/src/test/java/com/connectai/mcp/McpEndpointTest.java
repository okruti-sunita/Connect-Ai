package com.connectai.mcp;

import com.connectai.mcp.client.McpEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpEndpointTest {

    @Test
    void parsesOriginAndPath() {
        McpEndpoint endpoint = McpEndpoint.parse("https://example.com:8443/mcp");

        assertEquals("https://example.com:8443", endpoint.baseUrl());
        assertEquals("/mcp", endpoint.path());
    }

    @Test
    void defaultsPathToRootWhenOnlyOriginIsConfigured() {
        McpEndpoint endpoint = McpEndpoint.parse("http://localhost:8080");

        assertEquals("http://localhost:8080", endpoint.baseUrl());
        assertEquals("/", endpoint.path());
    }

    @Test
    void rejectsUnsupportedScheme() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> McpEndpoint.parse("ws://localhost:8080/mcp"));

        assertTrue(exception.getMessage().contains("http or https"));
    }

    @Test
    void rejectsQueryParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> McpEndpoint.parse("http://localhost:8080/mcp?tenant=one"));
    }
}
