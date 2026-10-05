package com.connectai.mcp.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretEncryptionServiceTest {

    @Test
    void encryptDecryptRoundTripReturnsOriginalSecret() {
        SecretEncryptionService service = new SecretEncryptionService("test-encryption-key");

        String encrypted = service.encrypt("Bearer-secret-123");

        assertNotNull(encrypted);
        assertNotEquals("Bearer-secret-123", encrypted);
        assertEquals("Bearer-secret-123", service.decrypt(encrypted));
    }

    @Test
    void encryptionUsesRandomIv() {
        SecretEncryptionService service = new SecretEncryptionService("test-encryption-key");

        String first = service.encrypt("same-secret");
        String second = service.encrypt("same-secret");

        assertNotEquals(first, second);
        assertEquals("same-secret", service.decrypt(first));
        assertEquals("same-secret", service.decrypt(second));
    }

    @Test
    void blankSecretIsStoredAsNull() {
        SecretEncryptionService service = new SecretEncryptionService("test-encryption-key");

        assertNull(service.encrypt(null));
        assertNull(service.encrypt("   "));
        assertNull(service.decrypt(null));
        assertNull(service.decrypt("   "));
    }
}
