package com.connectai.mcp.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpEndpointPolicyTest {
    @Test
    void rejectsLoopbackAndPrivateRemoteEndpointsByDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> McpEndpointPolicy.validate("http://127.0.0.1:8080/mcp", false));
        assertThrows(IllegalArgumentException.class,
                () -> McpEndpointPolicy.validate("https://10.1.2.3/mcp", false));
    }

    @Test
    void permitsLocalHttpOnlyWhenPrivateHostUseIsExplicitlyEnabled() {
        McpEndpoint endpoint = McpEndpointPolicy.validate("http://localhost:8080/mcp", true);
        assertEquals("/mcp", endpoint.path());
    }

    @Test
    void permitsHttpIpv4LoopbackWhenPrivateHostUseIsExplicitlyEnabled() {
        McpEndpoint endpoint = McpEndpointPolicy.validate("http://127.0.0.1:1/mcp", true);
        assertEquals("/mcp", endpoint.path());
    }

    @Test
    void keepsPublicHttpsEndpointAvailable() {
        McpEndpoint endpoint = McpEndpointPolicy.validate("https://8.8.8.8/mcp", false);
        assertEquals("https://8.8.8.8", endpoint.baseUrl());
    }
}
