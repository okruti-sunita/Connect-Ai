package com.connectai.service;

import com.connectai.agent.SafeDiagnostic;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Emits bounded, machine-readable lifecycle events without logging questions or provider payloads. */
final class InvestigationTrace {
    private static final Logger log = LoggerFactory.getLogger(InvestigationTrace.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.Set<String> SAFE_ATTRIBUTE_KEYS = java.util.Set.of(
            "questionLength", "toolCount", "warningCount", "connectionIds", "providerKeys",
            "route", "stepCount", "evidenceCount", "answerAvailable", "reason", "coverage",
            "successfulSteps", "failedSteps", "grounded", "errorType", "terminalReason");

    private InvestigationTrace() { }

    static void emit(UUID investigationId, String stage, String outcome, long durationMs,
                     Map<String, ?> safeAttributes) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "INVESTIGATION_TRACE");
        event.put("investigationId", investigationId.toString());
        event.put("traceId", investigationId.toString());
        event.put("stage", stage);
        event.put("eventType", "STARTED".equals(outcome) ? "STAGE_STARTED" : "STAGE_COMPLETED");
        event.put("timestamp", Instant.now().toString());
        event.put("durationMs", Math.max(0, durationMs));
        event.put("outcome", outcome);
        event.put("attributes", sanitizeAttributes(safeAttributes));
        log.info("{}", format(event));
    }

    static String format(Map<String, Object> event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            // The fallback contains only identifiers and fixed stage labels.
            return "{\"event\":\"INVESTIGATION_TRACE\",\"serializationFailed\":true}";
        }
    }

    static void bind(UUID investigationId) {
        String id = investigationId.toString();
        MDC.put("investigationId", id);
        MDC.put("traceId", id);
    }

    static void clear() {
        MDC.remove("investigationId");
        MDC.remove("traceId");
    }

    static Map<String, Object> sanitizeAttributes(Map<String, ?> attributes) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (attributes == null) return safe;
        attributes.forEach((key, value) -> {
            if (key == null || !SAFE_ATTRIBUTE_KEYS.contains(key) || value == null) return;
            if (value instanceof Number || value instanceof Boolean) {
                safe.put(key, value);
            } else if (value instanceof String text) {
                safe.put(key, SafeDiagnostic.sanitize(text, 240));
            } else if (value instanceof Iterable<?> values) {
                java.util.List<String> items = new java.util.ArrayList<>();
                for (Object item : values) {
                    if (items.size() == 20) break;
                    if (item != null) items.add(SafeDiagnostic.sanitize(String.valueOf(item), 120));
                }
                safe.put(key, items);
            }
        });
        return safe;
    }
}
