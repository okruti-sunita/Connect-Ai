package com.connectai.agent.learning;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolSafetyPolicy;
import com.connectai.agent.fastpath.AnswerTemplates;
import com.connectai.agent.fastpath.GenericToolMatcher;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the tool plan of an answered AI run into a replayable recipe, and fills a recipe in for a
 * new question.
 *
 * Values that came from the question become slots ({{owner}}, {{repo}}, {{key}}...), the signed-in
 * login or user id on each connection becomes {{login}} / {{#id}}. A plan whose arguments were
 * read from an earlier tool result (e.g. a repository name found by a search) depends on live data
 * and is not learned: replaying it with old values would answer from stale choices.
 */
public final class RecipeGeneralizer {

    static final int MAX_STEPS = 6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(#?)([a-z]+)}}");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9._-]+");

    /** One recorded call: the same tool on the same connection, with generalised arguments. */
    public record RecipeStep(UUID serverId, String toolName, Map<String, Object> arguments) {
    }

    /** The recipe, or why the run cannot be learned. */
    public record Outcome(Optional<List<RecipeStep>> steps, String reason) {
        static Outcome rejected(String reason) {
            return new Outcome(Optional.empty(), reason);
        }
    }

    /** Login and user id the identity tool returned on one connection. */
    public record Identity(String login, String id) {
    }

    private RecipeGeneralizer() {
    }

    public static Outcome generalize(QuestionTemplate question, String originalQuestion,
                                     AgentExecutionState execution, List<McpToolCandidate> tools) {
        List<AgentExecutionStep> successful = execution.steps().stream()
                .filter(step -> step.status() == AgentStepStatus.SUCCESS).toList();
        if (successful.isEmpty()) return Outcome.rejected("no successful tool call");
        if (successful.size() > MAX_STEPS) return Outcome.rejected("plan longer than " + MAX_STEPS + " calls");

        String questionLower = originalQuestion.toLowerCase(Locale.ROOT);
        Map<UUID, Identity> identities = new HashMap<>();
        List<String> earlierOutputs = new ArrayList<>();
        List<RecipeStep> steps = new ArrayList<>();

        for (AgentExecutionStep step : successful) {
            UUID server = step.plan().serverId();
            Optional<McpToolCandidate> tool = find(tools, server, step.plan().toolName());
            if (tool.isEmpty()) return Outcome.rejected(step.plan().toolName() + " is no longer connected");
            if (!ToolSafetyPolicy.isReadOnly(tool.get())) return Outcome.rejected(step.plan().toolName() + " is not read-only");

            Identity identity = identities.get(server);
            if (identity != null && identity.login() != null && questionLower.contains(identity.login().toLowerCase(Locale.ROOT))) {
                identity = new Identity(null, identity.id()); // the question names that user itself: keep it literal
            }
            Generalizing generalizing = new Generalizing(question.slots(), identity, questionLower, earlierOutputs);
            Object arguments = generalizing.value(step.plan().arguments());
            if (generalizing.rejection != null) return Outcome.rejected(generalizing.rejection);
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) arguments;
            steps.add(new RecipeStep(server, step.plan().toolName(), args));

            if (GenericToolMatcher.isIdentityTool(tool.get())) {
                identity(step).ifPresent(found -> identities.put(server, found));
            }
            earlierOutputs.add(outputText(step));
        }
        return new Outcome(Optional.of(steps), null);
    }

    /** Fills a recorded step in for a new question; empty when a slot it needs has no value. */
    public static Optional<Map<String, Object>> instantiate(RecipeStep step, Map<String, String> slots, Identity identity) {
        Map<String, String> values = new HashMap<>(slots);
        if (identity != null && identity.login() != null) values.put("login", identity.login());
        if (identity != null && identity.id() != null) values.put("id", identity.id());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) fill(step.arguments(), values);
            return Optional.of(args);
        } catch (IllegalStateException missing) {
            return Optional.empty();
        }
    }

    public static Optional<Identity> identity(AgentExecutionStep step) {
        for (ToolExecutionContent content : step.result().content()) {
            Optional<JsonNode> json = AnswerTemplates.json(content.text());
            if (json.isPresent()) {
                String login = AnswerTemplates.text(json.get(), "login", "username", "user.login", "name", "emailAddress", "email");
                String id = AnswerTemplates.text(json.get(), "accountId", "user_id", "id");
                return login == null && id == null ? Optional.empty() : Optional.of(new Identity(login, id));
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- internals

    private static final class Generalizing {
        final List<Map.Entry<String, String>> slots;
        final Identity identity;
        final String questionLower;
        final List<String> earlierOutputs;
        String rejection;

        Generalizing(Map<String, String> slots, Identity identity, String questionLower, List<String> earlierOutputs) {
            this.slots = slots.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getValue().length()).reversed())
                    .toList();
            this.identity = identity;
            this.questionLower = questionLower;
            this.earlierOutputs = earlierOutputs;
        }

        Object value(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), value(v)));
                return out;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(this::value).toList();
            }
            if (value instanceof String text) {
                return string(text);
            }
            if (value instanceof Number number) {
                return number(number);
            }
            return value;
        }

        private Object string(String text) {
            String out = text;
            for (Map.Entry<String, String> slot : slots) {
                if (slot.getValue().length() >= 2 && out.contains(slot.getValue())) {
                    out = out.replace(slot.getValue(), "{{" + slot.getKey() + "}}");
                }
            }
            if (identity != null && identity.login() != null && identity.login().length() >= 2 && out.contains(identity.login())) {
                out = out.replace(identity.login(), "{{login}}");
            }
            String residue = PLACEHOLDER.matcher(out).replaceAll(" ");
            for (String token : TOKEN_SPLIT.split(residue)) {
                if (token.length() >= 4 && !questionLower.contains(token.toLowerCase(Locale.ROOT)) && fromEarlierResult("\"" + token + "\"")) {
                    rejection = "the argument \"" + token + "\" was read from an earlier tool result, so the plan depends on live data";
                }
            }
            return out;
        }

        private Object number(Number number) {
            String text = number instanceof Double || number instanceof Float
                    ? String.valueOf(number.doubleValue()) : String.valueOf(number.longValue());
            for (Map.Entry<String, String> slot : slots) {
                if (slot.getValue().equals(text)) return "{{#" + slot.getKey() + "}}";
            }
            if (identity != null && text.equals(identity.id()) && !questionLower.contains(text)) return "{{#id}}";
            if (text.length() >= 4 && !questionLower.contains(text) && fromEarlierResult(":" + text)) {
                rejection = "the argument " + text + " was read from an earlier tool result, so the plan depends on live data";
            }
            return number;
        }

        private boolean fromEarlierResult(String needle) {
            return earlierOutputs.stream().anyMatch(output -> output.contains(needle));
        }
    }

    private static Object fill(Object value, Map<String, String> values) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), fill(v, values)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> fill(v, values)).toList();
        }
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher whole = PLACEHOLDER.matcher(text);
        if (whole.matches() && !whole.group(1).isEmpty()) {
            String filled = required(values, whole.group(2));
            try {
                return Long.parseLong(filled);
            } catch (NumberFormatException notNumeric) {
                return filled;
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(required(values, matcher.group(2))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) throw new IllegalStateException("no value for " + name);
        return value;
    }

    private static Optional<McpToolCandidate> find(List<McpToolCandidate> tools, UUID server, String name) {
        return tools.stream().filter(t -> t.serverId().equals(server) && t.toolName().equals(name)).findFirst();
    }

    private static String outputText(AgentExecutionStep step) {
        StringBuilder text = new StringBuilder();
        step.result().content().forEach(content -> {
            if (content.text() != null) text.append(content.text()).append('\n');
        });
        // Normalise "key": "value" spacing so the needle checks work on pretty-printed JSON too.
        return text.toString().replaceAll("\"\\s*:\\s*", "\":");
    }
}
