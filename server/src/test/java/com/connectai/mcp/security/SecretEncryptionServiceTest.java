package com.connectai.mcp.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretEncryptionServiceTest {

    @Test
    void rejectsMissingOrShortEncryptionKey() {
        assertThrows(IllegalStateException.class, () -> new SecretEncryptionService("short"));
        assertThrows(IllegalStateException.class, () ->
                new SecretEncryptionService("<generate-a-high-entropy-random-string-at-least-32-characters>"));
    }

    @Test
    void encryptDecryptRoundTripReturnsOriginalSecret() {
        SecretEncryptionService service = new SecretEncryptionService("test-only-encryption-key-long-enough-123456");

        String encrypted = service.encrypt("Bearer-secret-123");

        assertNotNull(encrypted);
        assertNotEquals("Bearer-secret-123", encrypted);
        assertEquals("Bearer-secret-123", service.decrypt(encrypted));
    }

    @Test
    void encryptionUsesRandomIv() {
        SecretEncryptionService service = new SecretEncryptionService("test-only-encryption-key-long-enough-123456");

        String first = service.encrypt("same-secret");
        String second = service.encrypt("same-secret");

        assertNotEquals(first, second);
        assertEquals("same-secret", service.decrypt(first));
        assertEquals("same-secret", service.decrypt(second));
    }

    @Test
    void blankSecretIsStoredAsNull() {
        SecretEncryptionService service = new SecretEncryptionService("test-only-encryption-key-long-enough-123456");

        assertNull(service.encrypt(null));
        assertNull(service.encrypt("   "));
        assertNull(service.decrypt(null));
        assertNull(service.decrypt("   "));
    }
}
