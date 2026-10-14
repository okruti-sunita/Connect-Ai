package com.connectai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationTraceTest {
    @Test
    void structuredTraceAttributesAreBoundedAndSecretSafe() {
        Map<String, Object> attributes = InvestigationTrace.sanitizeAttributes(Map.of(
                "toolCount", 4,
                "providerKeys", List.of("slack", "linear"),
                "terminalReason", "authorization=top-secret",
                "question", "private question text",
                "access_token", "top-secret"));

        assertThat(attributes).containsEntry("toolCount", 4)
                .containsEntry("providerKeys", List.of("slack", "linear"))
                .containsEntry("terminalReason", "authorization=[REDACTED]")
                .doesNotContainKeys("question", "access_token");
        assertThat(InvestigationTrace.format(Map.of("event", "INVESTIGATION_TRACE", "stage", "REQUEST_RECEIVED")))
                .contains("\"event\":\"INVESTIGATION_TRACE\"")
                .contains("\"stage\":\"REQUEST_RECEIVED\"");
    }
}
