package com.connectai.agent.fastpath;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic answers built from tool output. Every value printed is read from the JSON the
 * tool returned - nothing is paraphrased or inferred. A template returns empty when the output
 * does not have the expected shape; the caller then hands the evidence to the AI answer step.
 */
public final class AnswerTemplates {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_ITEMS = 15;

    private AnswerTemplates() {
    }

    public static Optional<JsonNode> json(String text) {
        if (text == null) return Optional.empty();
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return Optional.empty();
        try {
            return Optional.of(MAPPER.readTree(trimmed));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The list of records in a tool result: a root array, or the first array field (items, issues, ...). */
    public static Optional<JsonNode> records(JsonNode root) {
        if (root.isArray()) return Optional.of(root);
        for (String key : List.of("items", "issues", "pull_requests", "pullRequests", "commits", "releases",
                "branches", "nodes", "repositories", "data")) {
            if (root.path(key).isArray()) return Optional.of(root.path(key));
        }
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getValue().isArray() && (entry.getValue().isEmpty() || entry.getValue().get(0).isObject())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** First non-blank text among the given dotted paths. */
    public static String text(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode current = node;
            for (String part : path.split("\\.")) {
                current = current == null ? null : current.get(part);
            }
            if (current != null && current.isValueNode() && !current.asText().isBlank()) {
                return current.asText();
            }
        }
        return null;
    }

    static String date(String iso) {
        return iso == null ? null : iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    static String firstLine(String text) {
        if (text == null) return null;
        String line = text.strip().split("\\R", 2)[0];
        return line.length() > 120 ? line.substring(0, 117) + "..." : line;
    }

    static Integer total(JsonNode root, int listed) {
        return root.path("total_count").isNumber() ? root.path("total_count").asInt() : listed;
    }

    // ---------------------------------------------------------------- per intent

    static Optional<String> identity(JsonNode me, String citation) {
        String login = text(me, "login", "username");
        if (login == null) return Optional.empty();
        List<String> facts = new ArrayList<>();
        addIf(facts, text(me, "details.name", "name"), "name %s");
        addIf(facts, text(me, "details.company", "company"), "company %s");
        addIf(facts, text(me, "details.location", "location"), "location %s");
        String repos = text(me, "details.public_repos", "public_repos");
        if (repos != null) facts.add(repos + " public repositories");
        return Optional.of("signed in as **" + login + "**"
                + (facts.isEmpty() ? "" : " (" + String.join(", ", facts) + ")") + " [" + citation + "]");
    }

    static Optional<String> repositories(JsonNode root, String login, String citation) {
        Optional<JsonNode> list = records(root);
        if (list.isEmpty()) return Optional.empty();
        List<String> lines = new ArrayList<>();
        for (JsonNode repo : list.get()) {
            String name = text(repo, "name", "full_name");
            if (name == null) continue;
            List<String> meta = new ArrayList<>();
            if (repo.path("private").asBoolean(false)) meta.add("private");
            addIf(meta, text(repo, "language"), "%s");
            String stars = text(repo, "stargazers_count");
            if (stars != null && !"0".equals(stars)) meta.add("★ " + stars);
            addIf(meta, date(text(repo, "updated_at", "pushed_at")), "updated %s");
            lines.add("- `" + name + "`" + (meta.isEmpty() ? "" : " (" + String.join(", ", meta) + ")"));
            if (lines.size() >= MAX_ITEMS) break;
        }
        int total = total(root, lines.size());
        if (total == 0) return Optional.of("**" + login + "** owns no repositories [" + citation + "].");
        return Optional.of("**" + login + "** owns " + total + " repositor" + (total == 1 ? "y" : "ies")
                + " [" + citation + "]:\n" + String.join("\n", lines) + more(total, lines.size()));
    }

    static Optional<String> issuesOrPulls(JsonNode root, String what, String scope, String citation) {
        Optional<JsonNode> list = records(root);
        if (list.isEmpty() && !root.path("total_count").isNumber()) return Optional.empty();
        List<String> lines = new ArrayList<>();
        for (JsonNode item : list.orElse(MAPPER.createArrayNode())) {
            String title = text(item, "title");
            if (title == null) continue;
            String number = text(item, "number");
            List<String> meta = new ArrayList<>();
            addIf(meta, text(item, "state"), "%s");
            addIf(meta, text(item, "user.login", "author.login", "author"), "by %s");
            addIf(meta, date(text(item, "created_at", "createdAt")), "opened %s");
            String repo = repoFromUrl(text(item, "repository_url", "html_url"));
            lines.add("- " + (number == null ? "" : "#" + number + " ") + firstLine(title)
                    + (repo == null ? "" : " — " + repo) + (meta.isEmpty() ? "" : " (" + String.join(", ", meta) + ")"));
            if (lines.size() >= MAX_ITEMS) break;
        }
        int total = total(root, lines.size());
        if (total == 0) return Optional.of("There are no " + what + " " + scope + " [" + citation + "].");
        return Optional.of("There " + (total == 1 ? "is 1 " + singular(what) : "are " + total + " " + what) + " " + scope
                + " [" + citation + "]:\n" + String.join("\n", lines) + more(total, lines.size()));
    }

    static Optional<String> commits(JsonNode root, String slug, String citation) {
        Optional<JsonNode> list = records(root);
        if (list.isEmpty()) return Optional.empty();
        List<String> lines = new ArrayList<>();
        for (JsonNode commit : list.get()) {
            String sha = text(commit, "sha");
            String message = firstLine(text(commit, "commit.message", "message"));
            if (sha == null || message == null) continue;
            List<String> meta = new ArrayList<>();
            addIf(meta, text(commit, "commit.author.name", "author.login", "author.name"), "%s");
            addIf(meta, date(text(commit, "commit.author.date", "commit.committer.date", "date")), "%s");
            lines.add("- `" + sha.substring(0, Math.min(7, sha.length())) + "` " + message
                    + (meta.isEmpty() ? "" : " — " + String.join(", ", meta)));
            if (lines.size() >= MAX_ITEMS) break;
        }
        if (lines.isEmpty()) return Optional.of("`" + slug + "` has no commits [" + citation + "].");
        return Optional.of("Latest " + lines.size() + " commit" + (lines.size() == 1 ? "" : "s") + " in `" + slug
                + "` [" + citation + "]:\n" + String.join("\n", lines));
    }

    static Optional<String> releases(JsonNode root, String slug, String citation) {
        List<JsonNode> items = new ArrayList<>();
        if (root.isObject() && (root.has("tag_name") || root.has("name") && root.has("published_at"))) {
            items.add(root); // get_latest_release returns a single object
        } else {
            records(root).ifPresent(list -> list.forEach(items::add));
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode release : items) {
            String tag = text(release, "tag_name", "name");
            if (tag == null) continue;
            String name = text(release, "name");
            lines.add("- `" + tag + "`" + (name != null && !name.equals(tag) ? " " + firstLine(name) : "")
                    + (date(text(release, "published_at", "created_at")) == null ? "" : " — " + date(text(release, "published_at", "created_at"))));
            if (lines.size() >= MAX_ITEMS) break;
        }
        if (lines.isEmpty()) return Optional.of("`" + slug + "` has no releases [" + citation + "].");
        return Optional.of((lines.size() == 1 ? "Latest release" : "Releases") + " of `" + slug + "` [" + citation + "]:\n"
                + String.join("\n", lines));
    }

    static Optional<String> branches(JsonNode root, String slug, String citation) {
        Optional<JsonNode> list = records(root);
        if (list.isEmpty()) return Optional.empty();
        List<String> names = new ArrayList<>();
        for (JsonNode branch : list.get()) {
            String name = text(branch, "name");
            if (name != null) names.add("`" + name + "`" + (branch.path("protected").asBoolean(false) ? " (protected)" : ""));
        }
        if (names.isEmpty()) return Optional.of("`" + slug + "` has no branches [" + citation + "].");
        return Optional.of("`" + slug + "` has " + names.size() + " branch" + (names.size() == 1 ? "" : "es")
                + " [" + citation + "]: " + String.join(", ", names));
    }

    // ---------------------------------------------------------------- helpers

    private static void addIf(List<String> list, String value, String format) {
        if (value != null && !value.isBlank()) list.add(String.format(format, value));
    }

    private static String more(int total, int listed) {
        return total > listed ? "\n- … and " + (total - listed) + " more" : "";
    }

    private static String singular(String plural) {
        return plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
    }

    static String repoFromUrl(String url) {
        if (url == null) return null;
        var matcher = java.util.regex.Pattern.compile("(?:repos/|github\\.com/)([^/]+/[^/]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }
}
