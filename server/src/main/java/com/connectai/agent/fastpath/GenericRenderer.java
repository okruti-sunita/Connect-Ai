package com.connectai.agent.fastpath;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders any tool's JSON as a factual answer by recognising common field names across MCP
 * servers: titles (title, summary, fields.summary, name, subject, message, text), references
 * (key, number, sha, id), status (state, status, fields.status.name), people (author, user,
 * assignee, fields.assignee.displayName) and dates. Values are printed as returned; nothing is
 * interpreted. Returns empty when no record has a recognisable label, so the AI writes it instead.
 */
public final class GenericRenderer {

    private static final String[] LABEL = {"title", "summary", "fields.summary", "name", "login", "username", "subject",
            "commit.message", "message", "text", "full_name", "display_name", "displayName", "description"};
    private static final String[] REFERENCE = {"key", "number", "sha", "tag_name"};
    private static final String[] STATUS = {"state", "status", "fields.status.name", "status.name", "conclusion"};
    private static final String[] PERSON = {"user.login", "author.login", "assignee.login", "fields.assignee.displayName",
            "author.name", "commit.author.name", "owner.login", "user", "author", "assignee"};
    private static final String[] DATE = {"updated_at", "fields.updated", "updated", "created_at", "fields.created",
            "created", "commit.author.date", "published_at", "date", "ts"};

    private GenericRenderer() {
    }

    public static Optional<String> list(JsonNode root, String entity, String scope, String citation) {
        Optional<JsonNode> records = AnswerTemplates.records(root);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        int counted = 0;
        for (JsonNode item : records.get()) {
            if (!item.isObject()) {
                if (item.isValueNode()) lines.add("- " + item.asText());
                counted++;
                continue;
            }
            Optional<String> line = line(item);
            if (line.isPresent()) {
                counted++;
                if (lines.size() < AnswerTemplates.MAX_ITEMS) lines.add("- " + line.get());
                continue;
            }
            // Grouped results, e.g. [{"org": "acme", "teams": [{...}]}]: one level of nesting is flattened.
            Optional<JsonNode> nested = AnswerTemplates.records(item).filter(n -> n != item);
            if (nested.isEmpty()) {
                return Optional.empty(); // records exist but nothing recognisable: let the AI read them
            }
            String group = firstScalar(item);
            for (JsonNode child : nested.get()) {
                Optional<String> childLine = child.isObject() ? line(child) : Optional.of(child.asText());
                if (childLine.isEmpty()) return Optional.empty();
                counted++;
                if (lines.size() < AnswerTemplates.MAX_ITEMS) {
                    lines.add("- " + childLine.get() + (group == null ? "" : " — " + group));
                }
            }
        }
        int total = records.get().size() == counted ? AnswerTemplates.total(root, counted) : counted;
        String where = scope == null ? "" : " " + scope;
        if (total == 0) {
            return Optional.of("No " + entity + " found" + where + " [" + citation + "].");
        }
        String more = total > lines.size() ? "\n- … and " + (total - lines.size()) + " more" : "";
        return Optional.of(total + " " + entity + where + " [" + citation + "]:\n" + String.join("\n", lines) + more);
    }

    public static Optional<String> single(JsonNode item, String entity, String citation) {
        if (!item.isObject()) {
            return Optional.empty();
        }
        Optional<String> line = line(item);
        if (line.isEmpty()) {
            return Optional.empty();
        }
        String description = AnswerTemplates.firstLine(AnswerTemplates.text(item, "body", "fields.description", "description"));
        String url = AnswerTemplates.text(item, "html_url", "permalink", "web_url", "url");
        return Optional.of(capitalize(entity) + ": " + line.get() + " [" + citation + "]"
                + (description == null || line.get().contains(description) ? "" : "\n" + description)
                + (url == null ? "" : "\n" + url));
    }

    /** The signed-in user, from any identity tool's output. */
    public static Optional<String> identity(JsonNode me, String citation) {
        String login = AnswerTemplates.text(me, "login", "username", "user.login", "user", "name", "displayName",
                "emailAddress", "email", "user_id", "accountId");
        if (login == null) {
            return Optional.empty();
        }
        List<String> facts = new ArrayList<>();
        for (String[] field : new String[][]{{"details.name", "name", "displayName", "real_name"},
                {"details.company", "company", "team"}, {"details.location", "location"},
                {"emailAddress", "email"}}) {
            String value = AnswerTemplates.text(me, field);
            if (value != null && !value.equals(login)) facts.add(value);
        }
        return Optional.of("Signed in as **" + login + "**" + (facts.isEmpty() ? "" : " (" + String.join(", ", facts) + ")")
                + " [" + citation + "]");
    }

    private static Optional<String> line(JsonNode item) {
        String label = AnswerTemplates.firstLine(AnswerTemplates.text(item, LABEL));
        String reference = AnswerTemplates.text(item, REFERENCE);
        if (reference == null && label == null) {
            reference = AnswerTemplates.text(item, "id"); // bare ids only when nothing better identifies the record
        }
        if (label == null && reference == null) {
            return Optional.empty();
        }
        if (reference != null && reference.length() == 40 && reference.matches("[0-9a-f]+")) {
            reference = reference.substring(0, 7); // commit sha
        }
        List<String> meta = new ArrayList<>();
        add(meta, AnswerTemplates.text(item, STATUS));
        add(meta, AnswerTemplates.text(item, PERSON));
        add(meta, AnswerTemplates.date(AnswerTemplates.text(item, DATE)));
        String ref = reference == null || reference.equals(label) ? "" : (reference.matches("\\d+") ? "#" + reference : "`" + reference + "`") + " ";
        return Optional.of(ref + (label == null ? "" : label) + (meta.isEmpty() ? "" : " (" + String.join(", ", meta) + ")"));
    }

    /** The first plain value of a group record (its name: org, project, team...). */
    private static String firstScalar(JsonNode item) {
        var fields = item.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) return entry.getValue().asText();
        }
        return null;
    }

    private static void add(List<String> list, String value) {
        if (value != null && !value.isBlank() && list.stream().noneMatch(value::equals)) list.add(value);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
