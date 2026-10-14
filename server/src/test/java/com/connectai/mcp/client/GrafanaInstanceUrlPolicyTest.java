package com.connectai.mcp.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrafanaInstanceUrlPolicyTest {
    @Test
    void acceptsHttpsGrafanaBaseUrlAndPreservesSubpath() {
        assertEquals("https://8.8.8.8/grafana",
                GrafanaInstanceUrlPolicy.validate("https://8.8.8.8/grafana/", false));
    }

    @Test
    void rejectsHttpExceptOptedInLocalDevelopment() {
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("http://8.8.8.8", true));
        assertEquals("http://localhost:3000",
                GrafanaInstanceUrlPolicy.validate("http://localhost:3000/", true));
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("http://localhost:3000", false));
    }

    @Test
    void requiresExplicitPrivateNetworkOptInAndAlwaysRejectsMetadataAddresses() {
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("https://10.0.0.5", false));
        assertEquals("https://10.0.0.5",
                GrafanaInstanceUrlPolicy.validate("https://10.0.0.5", true));
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("https://169.254.169.254", true));
    }

    @Test
    void rejectsCredentialsQueryAndFragment() {
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("https://user:secret@8.8.8.8", false));
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("https://8.8.8.8?token=secret", false));
        assertThrows(IllegalArgumentException.class,
                () -> GrafanaInstanceUrlPolicy.validate("https://8.8.8.8/#fragment", false));
    }
}
