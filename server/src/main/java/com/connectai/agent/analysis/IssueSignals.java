package com.connectai.agent.analysis;

import com.connectai.agent.fastpath.AnswerTemplates;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an issue says about its own cause, read from the tracker's JSON: when it was raised, its type
 * and project, and the concrete clues in its text - file names, exception names, repositories,
 * commit SHAs and distinctive words. Every clue is copied from the issue text, never inferred.
 */
public record IssueSignals(
        String key,
        String summary,
        String description,
        String project,
        String issueType,
        String status,
        String priority,
        List<String> components,
        Instant created,
        Instant resolved,
        Set<String> files,
        Set<String> exceptions,
        Set<String> repositories,
        Set<String> shas,
        Set<String> keywords,
        String url) {

    public static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d{1,7})\\b");

    private static final Pattern FILE = Pattern.compile(
            "(?<![\\w/.-])((?:[\\w.-]+/)*[\\w-]+\\.(?:java|kt|scala|ts|tsx|js|jsx|py|go|rb|cs|php|rs|swift|c|cpp|h|"
                    + "sql|yml|yaml|json|xml|properties|gradle|tf|sh|html|css|scss|vue))\\b");
    private static final Pattern EXCEPTION = Pattern.compile("\\b([A-Z][A-Za-z0-9]*(?:Exception|Error))\\b");
    private static final Pattern SLUG = Pattern.compile(
            "(?<![\\w./-])([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})(?![\\w/])");
    private static final Pattern SHA = Pattern.compile("\\b([0-9a-f]{7,40})\\b");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{4,}");
    private static final Set<String> STOP = Set.of(
            "about", "after", "again", "against", "being", "below", "between", "cannot", "could", "doing", "during",
            "every", "first", "found", "from", "getting", "having", "issue", "into", "occurs", "other", "should",
            "since", "their", "there", "these", "thing", "think", "those", "through", "under", "until", "where",
            "which", "while", "would", "error", "errors", "users", "user", "please", "steps", "expected", "actual",
            "result", "results", "still", "problem", "working", "works", "never", "always", "sometimes", "today",
            "yesterday", "when", "with", "without", "page", "click", "clicking", "description", "summary", "https");

    public static IssueSignals from(JsonNode issue) {
        String key = AnswerTemplates.text(issue, "key");
        String summary = nullToEmpty(AnswerTemplates.text(issue, "summary", "fields.summary"));
        String description = nullToEmpty(AnswerTemplates.text(issue, "description", "fields.description"));
        JsonNode comments = issue.path("comments");
        StringBuilder commentText = new StringBuilder();
        if (comments.isArray()) {
            comments.forEach(c -> commentText.append(' ').append(c.path("body").asText("")));
        }
        String text = summary + "\n" + description + "\n" + commentText;

        List<String> components = new ArrayList<>();
        JsonNode componentNode = issue.has("components") ? issue.path("components") : issue.path("fields").path("components");
        if (componentNode.isArray()) {
            componentNode.forEach(c -> components.add(c.isTextual() ? c.asText() : c.path("name").asText()));
        }
        String project = AnswerTemplates.text(issue, "project.key", "fields.project.key");
        if (project == null && key != null && key.contains("-")) {
            project = key.substring(0, key.indexOf('-'));
        }
        return new IssueSignals(
                key,
                summary,
                description,
                project,
                AnswerTemplates.text(issue, "issue_type.name", "fields.issuetype.name", "issuetype.name"),
                AnswerTemplates.text(issue, "status.name", "fields.status.name"),
                AnswerTemplates.text(issue, "priority.name", "fields.priority.name"),
                components,
                parseDate(AnswerTemplates.text(issue, "created", "fields.created")),
                parseDate(AnswerTemplates.text(issue, "resolutiondate", "fields.resolutiondate")),
                all(FILE, text, 1),
                all(EXCEPTION, text, 1),
                slugs(text),
                shas(text),
                keywords(summary + " " + description),
                AnswerTemplates.text(issue, "browse_url", "url"));
    }

    /** Lower-case file base names ("PaymentService.java"), for matching against changed paths. */
    public Set<String> fileNames() {
        Set<String> names = new LinkedHashSet<>();
        files.forEach(f -> names.add(f.substring(f.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)));
        return names;
    }

    // ---------------------------------------------------------------- parsing

    private static final DateTimeFormatter JIRA_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    /** Jira ISO timestamps ("2026-10-10T12:34:56.000+0000"), ISO with Z, or "yyyy-MM-dd HH:mm:ss <zone>" (local). */
    public static Instant parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v, JIRA_OFFSET).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        if (v.length() >= 19) {
            try {
                // mcp-atlassian renders "created" in the server's local zone; this backend runs alongside it.
                return LocalDateTime.parse(v.substring(0, 19).replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        if (v.length() >= 10) {
            try {
                return java.time.LocalDate.parse(v.substring(0, 10)).atStartOfDay(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static Set<String> all(Pattern pattern, String text, int group) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find() && out.size() < 10) out.add(m.group(group));
        return out;
    }

    private static Set<String> slugs(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SLUG.matcher(text);
        while (m.find() && out.size() < 5) {
            String repo = m.group(2).replaceAll("[.?!,;:)]+$", "");
            if (FILE.matcher(m.group(0)).find() || m.group(1).matches("\\d+")) continue; // a path or a date, not a repo
            out.add(m.group(1) + "/" + repo);
        }
        return out;
    }

    private static Set<String> shas(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SHA.matcher(text);
        while (m.find() && out.size() < 5) {
            String s = m.group(1);
            if (s.matches(".*[a-f].*") && s.matches(".*\\d.*")) out.add(s);
        }
        return out;
    }

    private static Set<String> keywords(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (!STOP.contains(w)) counts.merge(w, 1, Integer::sum);
        }
        Set<String> out = new LinkedHashSet<>();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8).forEach(e -> out.add(e.getKey()));
        return out;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
