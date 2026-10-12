package com.connectai.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates planner-produced tool arguments against the tool's real MCP input schema
 * immediately before invocation.
 *
 * Supports the JSON Schema subset MCP servers use in practice: type (single or list),
 * properties, required, enum, items, nested objects, minimum/maximum and
 * additionalProperties=false. Unknown keywords (anyOf, oneOf, format...) are accepted
 * as-is rather than rejected, so legitimate schemas are never blocked.
 *
 * Two small, lossless normalisations are applied because small local models produce
 * them constantly: optional properties set to null are dropped, and numeric/boolean
 * strings ("5", "true") are converted when the schema demands a number/boolean.
 * Properties the schema does not declare are dropped unless the schema explicitly
 * allows additional properties, and rejected when it forbids them.
 */
public final class ToolArgumentValidator {

    private ToolArgumentValidator() {
    }

    public record Result(Map<String, Object> arguments, List<String> errors, List<String> adjustments) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result validate(Map<String, Object> schema, Map<String, Object> arguments) {
        List<String> errors = new ArrayList<>();
        List<String> adjustments = new ArrayList<>();
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        Map<String, Object> rootSchema = schema == null ? Map.of() : schema;

        Object cleaned = validateObject("arguments", rootSchema, input, errors, adjustments);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = cleaned instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : new LinkedHashMap<>(input);
        return new Result(result, List.copyOf(errors), List.copyOf(adjustments));
    }

    private static Map<String, Object> validateObject(
            String path,
            Map<String, Object> schema,
            Map<?, ?> value,
            List<String> errors,
            List<String> adjustments) {

        Map<String, Object> properties = asMap(schema.get("properties"));
        List<String> required = asStringList(schema.get("required"));
        Object additional = schema.get("additionalProperties");
        boolean forbidsExtra = Boolean.FALSE.equals(additional);
        boolean allowsExtra = Boolean.TRUE.equals(additional) || additional instanceof Map<?, ?>
                || properties == null;

        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object item = entry.getValue();
            String childPath = path + "." + key;
            Map<String, Object> propertySchema = properties == null ? null : asMap(properties.get(key));

            if (propertySchema == null) {
                if (forbidsExtra) {
                    errors.add(childPath + " is not a parameter of this tool");
                } else if (allowsExtra) {
                    out.put(key, item);
                } else {
                    adjustments.add("dropped undeclared parameter " + childPath);
                }
                continue;
            }

            if (item == null && !required.contains(key) && !allowsNull(propertySchema)) {
                adjustments.add("dropped null optional parameter " + childPath);
                continue;
            }

            out.put(key, validateValue(childPath, propertySchema, item, errors, adjustments));
        }

        for (String name : required) {
            if (!out.containsKey(name) || out.get(name) == null && !allowsNull(asMap(
                    properties == null ? null : properties.get(name)))) {
                errors.add(path + "." + name + " is required");
            }
        }

        return out;
    }

    private static Object validateValue(
            String path,
            Map<String, Object> schema,
            Object value,
            List<String> errors,
            List<String> adjustments) {

        if (schema == null) {
            return value;
        }

        List<String> types = typesOf(schema);
        Object current = value;

        if (!types.isEmpty() && !matchesAny(types, current)) {
            Object coerced = coerce(types, current);
            if (coerced != null && matchesAny(types, coerced)) {
                adjustments.add("converted " + path + " to " + String.join("|", types));
                current = coerced;
            } else {
                errors.add(path + " must be " + String.join(" or ", types)
                        + " but was " + describe(current));
                return current;
            }
        }

        List<?> allowed = schema.get("enum") instanceof List<?> list ? list : null;
        if (allowed != null && !allowed.isEmpty() && !enumContains(allowed, current)) {
            errors.add(path + " must be one of " + allowed);
        }

        if (current instanceof Number number) {
            if (schema.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue()) {
                errors.add(path + " must be >= " + min);
            }
            if (schema.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue()) {
                errors.add(path + " must be <= " + max);
            }
        }

        if (current instanceof Map<?, ?> map && (types.isEmpty() || types.contains("object"))) {
            return validateObject(path, schema, map, errors, adjustments);
        }

        if (current instanceof Collection<?> collection) {
            Map<String, Object> itemSchema = asMap(schema.get("items"));
            List<Object> out = new ArrayList<>();
            int index = 0;
            for (Object element : collection) {
                out.add(itemSchema == null
                        ? element
                        : validateValue(path + "[" + index + "]", itemSchema, element, errors, adjustments));
                index++;
            }
            return out;
        }

        return current;
    }

    private static boolean matchesAny(List<String> types, Object value) {
        for (String type : types) {
            if (matches(type, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "null" -> value == null;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "array" -> value instanceof Collection<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true; // unknown type keyword: do not block
        };
    }

    private static Object coerce(List<String> types, Object value) {
        if (value instanceof Double d && types.contains("integer") && d == Math.rint(d) && !d.isInfinite()) {
            return d.longValue();
        }
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        try {
            if (types.contains("integer") && trimmed.matches("-?\\d{1,18}")) {
                return Long.parseLong(trimmed);
            }
            if (types.contains("number") && trimmed.matches("-?\\d+(\\.\\d+)?")) {
                return Double.parseDouble(trimmed);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (types.contains("boolean") && ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed))) {
            return Boolean.parseBoolean(trimmed.toLowerCase());
        }
        return null;
    }

    private static boolean enumContains(List<?> allowed, Object value) {
        for (Object option : allowed) {
            if (Objects.equals(option, value)) {
                return true;
            }
            if (option instanceof Number a && value instanceof Number b && a.doubleValue() == b.doubleValue()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowsNull(Map<String, Object> schema) {
        return schema != null && typesOf(schema).contains("null");
    }

    static List<String> typesOf(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof String single) {
            return List.of(single);
        }
        if (type instanceof List<?> list) {
            List<String> types = new ArrayList<>();
            list.forEach(item -> types.add(String.valueOf(item)));
            return types;
        }
        return List.of();
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "a string";
        if (value instanceof Boolean) return "a boolean";
        if (value instanceof Number) return "a number";
        if (value instanceof Collection<?>) return "an array";
        if (value instanceof Map<?, ?>) return "an object";
        return value.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        list.forEach(item -> out.add(String.valueOf(item)));
        return out;
    }
}
