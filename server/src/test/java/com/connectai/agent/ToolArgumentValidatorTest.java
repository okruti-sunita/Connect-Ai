package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentValidatorTest {

    private static final Map<String, Object> ISSUE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "owner", Map.of("type", "string"),
                    "repo", Map.of("type", "string"),
                    "state", Map.of("type", "string", "enum", List.of("open", "closed")),
                    "perPage", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                    "labels", Map.of("type", "array", "items", Map.of("type", "string")),
                    "filter", Map.of("type", "object",
                            "properties", Map.of("since", Map.of("type", "string")),
                            "required", List.of("since"))),
            "required", List.of("owner", "repo"));

    @Test
    void acceptsValidArguments() {
        var result = ToolArgumentValidator.validate(ISSUE_SCHEMA,
                Map.of("owner", "octo", "repo", "demo", "state", "open", "perPage", 10L, "labels", List.of("bug")));

        assertThat(result.valid()).isTrue();
        assertThat(result.arguments()).containsEntry("owner", "octo").containsEntry("perPage", 10L);
    }

    @Test
    void reportsMissingRequiredArguments() {
        var result = ToolArgumentValidator.validate(ISSUE_SCHEMA, Map.of("owner", "octo"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("arguments.repo is required");
    }

    @Test
    void reportsWrongTypeEnumAndRange() {
        var result = ToolArgumentValidator.validate(ISSUE_SCHEMA,
                Map.of("owner", 5L, "repo", "demo", "state", "merged", "perPage", 500L));

        assertThat(result.errors()).anyMatch(e -> e.startsWith("arguments.owner must be string"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("arguments.state must be one of"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("arguments.perPage must be <= 100"));
    }

    @Test
    void validatesNestedObjectsAndArrayItems() {
        var result = ToolArgumentValidator.validate(ISSUE_SCHEMA,
                Map.of("owner", "o", "repo", "r", "labels", List.of("ok", 3L), "filter", Map.of()));

        assertThat(result.errors()).contains("arguments.labels[1] must be string but was a number",
                "arguments.filter.since is required");
    }

    @Test
    void convertsNumericAndBooleanStringsLosslessly() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "perPage", Map.of("type", "integer"),
                "draft", Map.of("type", "boolean")));

        var result = ToolArgumentValidator.validate(schema, Map.of("perPage", "25", "draft", "true"));

        assertThat(result.valid()).isTrue();
        assertThat(result.arguments()).containsEntry("perPage", 25L).containsEntry("draft", true);
        assertThat(result.adjustments()).hasSize(2);
    }

    @Test
    void dropsNullOptionalAndRejectsUndeclaredParameters() {
        Map<String, Object> args = new HashMap<>();
        args.put("owner", "o");
        args.put("repo", "r");
        args.put("state", null);
        args.put("hallucinated", "x");

        var result = ToolArgumentValidator.validate(ISSUE_SCHEMA, args);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("arguments.hallucinated is not a parameter of this tool");
        assertThat(result.arguments()).containsOnlyKeys("owner", "repo");
    }

    @Test
    void rejectsUndeclaredParametersWhenSchemaForbidsThem() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(),
                "additionalProperties", false);

        var result = ToolArgumentValidator.validate(schema, Map.of("login", "me"));

        assertThat(result.errors()).containsExactly("arguments.login is not a parameter of this tool");
    }

    @Test
    void noArgumentToolAcceptsEmptyObject() {
        var result = ToolArgumentValidator.validate(Map.of("type", "object", "properties", Map.of()), Map.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.arguments()).isEmpty();
    }
}
