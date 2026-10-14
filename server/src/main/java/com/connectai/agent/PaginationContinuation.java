package com.connectai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Builds a validated next-page call only when a provider response explicitly signals more data. */
public final class PaginationContinuation {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PaginationContinuation() { }

    public static Optional<ToolSelectionPlan> next(AgentExecutionStep step, McpToolCandidate tool) {
        if (step == null || tool == null || step.status() != AgentStepStatus.SUCCESS) return Optional.empty();
        JsonNode root = resultTree(step.result());
        String cursor = cursor(root);
        boolean hasMore = booleanField(root, "hasmore", "morepages", "hasnextpage");
        if ((cursor == null || cursor.isBlank()) && !hasMore) return Optional.empty();

        Map<String, Object> properties = properties(tool.inputSchema());
        Map<String, Object> args = new LinkedHashMap<>(step.plan().arguments());
        String cursorParameter = matchingProperty(properties, "cursor", "continuationtoken", "nexttoken",
                "pagetoken", "nextpagetoken", "after");
        if (cursor != null && !cursor.isBlank() && cursor.length() <= 2048 && cursorParameter != null) {
            args.put(cursorParameter, cursor);
        } else if (hasMore) {
            String page = matchingProperty(properties, "page", "pagenumber", "pageindex");
            if (page != null) {
                Object current = args.get(page);
                long next = current instanceof Number number ? number.longValue() + 1 : 2;
                args.put(page, next);
            } else {
                String offset = matchingProperty(properties, "offset", "start");
                Object limit = firstValue(args, "limit", "pagesize", "perpage", "count", "first");
                Object current = args.get(offset);
                if (offset == null || !(limit instanceof Number size) || size.longValue() <= 0) return Optional.empty();
                long next = (current instanceof Number number ? number.longValue() : 0) + size.longValue();
                args.put(offset, next);
            }
        } else {
            return Optional.empty();
        }

        ToolArgumentValidator.Result validation = ToolArgumentValidator.validate(tool.inputSchema(), args);
        if (!validation.valid() || validation.arguments().equals(step.plan().arguments())) return Optional.empty();
        return Optional.of(new ToolSelectionPlan(step.plan().serverId(), step.plan().toolName(),
                validation.arguments(), "Continue pagination because the provider explicitly reported more results"));
    }

    private static JsonNode resultTree(ToolExecutionResult result) {
        JsonNode structured = result.structuredContent() == null ? null : MAPPER.valueToTree(result.structuredContent());
        if (structured != null && containsPagination(structured)) return structured;
        if (result.content() != null) {
            for (ToolExecutionContent content : result.content()) {
                if (content == null || content.text() == null) continue;
                try {
                    JsonNode parsed = MAPPER.readTree(content.text());
                    if (containsPagination(parsed)) return parsed;
                } catch (Exception ignored) { /* Plain text does not establish pagination. */ }
            }
        }
        return structured;
    }

    private static boolean containsPagination(JsonNode node) {
        return cursor(node) != null || booleanField(node, "hasmore", "morepages", "hasnextpage");
    }

    private static String cursor(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = canonical(field.getKey());
                if (java.util.Set.of("nextcursor", "nexttoken", "nextpagetoken", "continuationtoken").contains(key)
                        && field.getValue().isTextual() && !field.getValue().asText().isBlank()) return field.getValue().asText();
                String nested = cursor(field.getValue());
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = cursor(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean booleanField(JsonNode node, String... names) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = canonical(field.getKey());
                for (String name : names) if (key.equals(name) && field.getValue().asBoolean(false)) return true;
                if (booleanField(field.getValue(), names)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (booleanField(child, names)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return schema.get("properties") instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static String matchingProperty(Map<String, Object> properties, String... candidates) {
        for (String candidate : candidates) {
            for (String property : properties.keySet()) if (canonical(property).equals(candidate)) return property;
        }
        return null;
    }

    private static Object firstValue(Map<String, Object> values, String... candidates) {
        for (String candidate : candidates) {
            for (var entry : values.entrySet()) if (canonical(entry.getKey()).equals(candidate)) return entry.getValue();
        }
        return null;
    }

    private static String canonical(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
