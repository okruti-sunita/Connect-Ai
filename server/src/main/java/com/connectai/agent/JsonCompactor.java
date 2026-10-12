package com.connectai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes API noise from JSON tool output so prompts and stored evidence spend their limited
 * size on content: API link fields (every "*_url" except "html_url"), node/gravatar ids, nulls
 * and empty values. Nothing is reworded or invented; text that is not valid JSON is returned
 * unchanged.
 */
public final class JsonCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DROPPED = Set.of("node_id", "gravatar_id", "url", "avatar_url", "_links");

    private JsonCompactor() {
    }

    public static String compact(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return text;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            clean(node);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return text; // not (complete) JSON: leave untouched
        }
    }

    private static void clean(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> remove = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                JsonNode value = object.get(name);
                boolean apiLink = name.endsWith("_url") && !name.equals("html_url");
                boolean empty = value.isNull()
                        || (value.isTextual() && value.asText().isEmpty())
                        || ((value.isArray() || value.isObject()) && value.isEmpty());
                if (apiLink || DROPPED.contains(name) || empty) {
                    remove.add(name);
                } else {
                    clean(value);
                }
            });
            object.remove(remove);
        } else if (node instanceof ArrayNode array) {
            array.forEach(JsonCompactor::clean);
        }
    }
}
