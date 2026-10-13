package com.connectai.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, dependency-free narrowing of a large live tool catalog to the few
 * candidates most relevant to a question.
 *
 * Small local models (for example qwen3:1.7b) cannot reliably choose among dozens of
 * tool schemas, and large catalogs overflow their context window. The planner therefore
 * only ever shows the model this shortlist. Every shortlisted entry is still a real
 * candidate from the live catalog: the shortlister can only remove tools, never add them.
 *
 * Scoring is plain token overlap between the question and the tool's name, title and
 * description, with name matches weighted highest and a small synonym table for
 * phrasing that engineers commonly use ("my account" -> the authenticated user).
 */
public final class ToolShortlister {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "is", "are",
            "be", "it", "this", "that", "use", "using", "available", "tool", "tools", "mcp", "please",
            "do", "not", "can", "you", "what", "which", "who", "how", "why", "when", "from", "any",
            "real", "result", "results", "invent", "evidence", "report", "about", "all", "get", "list");

    /** Expansions applied to question tokens only. Kept deliberately small and generic. */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("my", List.of("me", "authenticated", "user")),
            Map.entry("me", List.of("authenticated", "user")),
            Map.entry("i", List.of("me", "authenticated")),
            Map.entry("account", List.of("user", "profile", "me")),
            Map.entry("login", List.of("user", "username", "me")),
            Map.entry("username", List.of("user", "login")),
            Map.entry("whoami", List.of("me", "authenticated", "user")),
            Map.entry("identity", List.of("me", "authenticated", "user")),
            Map.entry("repo", List.of("repository", "repositories")),
            Map.entry("repos", List.of("repository", "repositories")),
            Map.entry("pr", List.of("pull", "request")),
            Map.entry("prs", List.of("pull", "requests")),
            Map.entry("bug", List.of("issue", "issues")),
            Map.entry("ticket", List.of("issue", "issues")),
            Map.entry("commit", List.of("commits")),
            Map.entry("changed", List.of("commits", "changes")),
            Map.entry("release", List.of("releases", "tag", "tags")),
            Map.entry("logs", List.of("log", "search")),
            Map.entry("errors", List.of("error", "log")),
            Map.entry("code", List.of("search", "file", "contents")),
            Map.entry("action", List.of("actions", "workflow", "workflows", "ci")),
            Map.entry("actions", List.of("action", "workflow", "workflows", "ci")),
            Map.entry("ci", List.of("workflow", "workflows", "actions", "build")),
            Map.entry("pipeline", List.of("workflow", "workflows", "actions")),
            Map.entry("build", List.of("workflow", "workflows", "job", "jobs")),
            Map.entry("failures", List.of("failure", "failed", "error", "errors", "conclusion")),
            Map.entry("failed", List.of("failure", "failures", "error", "conclusion")),
            Map.entry("failing", List.of("failure", "failed", "error")));

    /**
     * Words that name the platform or the kind of container rather than what is being asked
     * about. They are ignored when deciding whether a tool covers the question's topic, since
     * nearly every tool of a GitHub server mentions "GitHub repository".
     */
    private static final Set<String> GENERIC_TOPIC_WORDS = Set.of(
            "github", "gitlab", "repo", "repos", "repository", "repositories", "project", "projects",
            "org", "organization", "which", "most", "least", "many", "much", "has", "have", "had", "does",
            "did", "top", "highest", "lowest", "show", "tell", "give", "find", "identify", "there", "their",
            "please", "help", "need", "want", "know", "check", "look", "data", "info", "information",
            "connection", "connections", "each", "every", "both", "all");

    /** Only the start of a description is used for topic coverage: long descriptions mention everything. */
    private static final int TOPIC_DESCRIPTION_CHARS = 200;

    private ToolShortlister() {
    }

    /**
     * Topic gate result. {@code topics} are the question's content words; {@code covering}
     * are catalog tools whose name or description covers at least one of them;
     * {@code uncovered} are topics no tool covers.
     */
    public record TopicCoverage(List<String> topics, List<String> uncovered, List<McpToolCandidate> covering) {
        public boolean noToolCoversTheTopic() {
            return !topics.isEmpty() && covering.isEmpty();
        }

        /**
         * Uncovered topics that name a kind of data (failures, workflows, pull requests...), as
         * opposed to entity names (a repository called "api"). No connected tool provides them,
         * so an answer can at most partially cover the question.
         */
        public List<String> uncoveredCapabilities() {
            return uncovered.stream().filter(ToolShortlister::isCapabilityWord).toList();
        }
    }

    /** Kinds of engineering data beyond the synonym table (singular forms; plurals match by prefix). */
    private static final Set<String> EXTRA_CAPABILITY_WORDS = Set.of(
            "deployment", "alert", "vulnerability", "secret", "security", "tag", "branch", "label",
            "milestone", "team", "member", "collaborator", "star", "fork", "contributor", "review",
            "comment", "metric", "incident", "job", "workflow", "release", "issue", "commit", "file",
            "notification", "discussion", "package", "environment", "runner", "artifact", "check");

    private static final Set<String> CAPABILITY_WORDS = capabilityWords();

    private static Set<String> capabilityWords() {
        Set<String> words = new HashSet<>(SYNONYMS.keySet());
        SYNONYMS.values().forEach(words::addAll);
        words.addAll(EXTRA_CAPABILITY_WORDS);
        // Identity words select get_me-like tools through the synonyms, but never gate on their own.
        words.removeAll(Set.of("me", "my", "i", "user", "authenticated", "search", "list", "get"));
        return Set.copyOf(words);
    }

    private static boolean isCapabilityWord(String token) {
        if (CAPABILITY_WORDS.contains(token)) {
            return true;
        }
        if (token.length() < 4) {
            return false;
        }
        for (String word : CAPABILITY_WORDS) {
            if (word.length() >= 4 && (token.startsWith(word) || word.startsWith(token))) {
                return true;
            }
        }
        return false;
    }

    /** Generic words, tolerating one typo in longer words ("guthub"). */
    private static boolean isGeneric(String token) {
        if (GENERIC_TOPIC_WORDS.contains(token)) {
            return true;
        }
        if (token.length() < 5) {
            return false;
        }
        for (String generic : GENERIC_TOPIC_WORDS) {
            if (generic.length() >= 5 && Math.abs(generic.length() - token.length()) <= 1 && editDistanceAtMostOne(generic, token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean editDistanceAtMostOne(String a, String b) {
        int i = 0;
        int j = 0;
        int edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) {
                return false;
            }
            if (a.length() > b.length()) {
                i++;
            } else if (a.length() < b.length()) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    /**
     * Deterministic check that a tool can plausibly provide what the question is about. A
     * small model will otherwise happily use a loosely related tool (for example a keyword
     * search of public repositories for a question about the user's CI failures) and present
     * the result as an answer.
     */
    public static TopicCoverage topicCoverage(String question, List<McpToolCandidate> catalog) {
        List<String> topics = new ArrayList<>();
        // Only words that name a kind of data gate tools. Entity names ("PAY-123", "octo/api") and
        // ordinary verbs never do, so generic search tools stay available for them.
        for (String token : tokens(question)) {
            if (!isGeneric(token) && isCapabilityWord(token)) {
                topics.add(token);
            }
        }

        Set<String> covered = new LinkedHashSet<>();
        List<McpToolCandidate> covering = new ArrayList<>();
        for (McpToolCandidate tool : catalog == null ? List.<McpToolCandidate>of() : catalog) {
            String description = tool.description();
            Set<String> toolTokens = tokens(tool.toolName() + " " + tool.title() + " "
                    + description.substring(0, Math.min(description.length(), TOPIC_DESCRIPTION_CHARS)));
            boolean coversAny = false;
            for (String topic : topics) {
                Set<String> variants = new HashSet<>(SYNONYMS.getOrDefault(topic, List.of()));
                if (topic.endsWith("s") && topic.length() > 3) {
                    // plural form: "accounts" -> the synonyms of "account"
                    variants.addAll(SYNONYMS.getOrDefault(topic.substring(0, topic.length() - 1), List.of()));
                }
                variants.add(topic);
                for (String variant : variants) {
                    if (matchesWord(toolTokens, variant)) {
                        covered.add(topic);
                        coversAny = true;
                        break;
                    }
                }
            }
            if (coversAny) {
                covering.add(tool);
            }
        }

        List<String> uncovered = topics.stream().filter(topic -> !covered.contains(topic)).toList();
        return new TopicCoverage(List.copyOf(topics), uncovered, List.copyOf(covering));
    }

    public record Scored(McpToolCandidate tool, double score) {
    }

    /**
     * Returns at most {@code limit} candidates ordered by relevance. When nothing matches
     * at all, returns an empty list: the planner then reports "no relevant tool" instead of
     * asking a small model to guess among unrelated tools.
     */
    /**
     * The topic-covering tools plus every tool whose name names something in the question.
     * "repositories" is too generic to gate on (every GitHub tool mentions it), but in
     * "which repositories does each account own" search_repositories is exactly the right tool.
     */
    public static List<McpToolCandidate> withNameMatches(
            String question, List<McpToolCandidate> covering, List<McpToolCandidate> catalog) {
        Set<String> questionTokens = expand(tokens(question));
        List<McpToolCandidate> eligible = new ArrayList<>(covering);
        for (McpToolCandidate tool : catalog) {
            if (eligible.contains(tool)) {
                continue;
            }
            Set<String> nameTokens = tokens(tool.toolName() + " " + tool.title());
            boolean named = nameTokens.stream().anyMatch(token -> token.length() >= 4
                    && matchesWord(questionTokens, token));
            if (named) {
                eligible.add(tool);
            }
        }
        return eligible;
    }

    public static List<McpToolCandidate> shortlist(String question, List<McpToolCandidate> catalog, int limit) {
        // The limit counts distinct tools: with several connections of the same server, each chosen
        // tool is kept on every connection instead of crowding other tools out of the shortlist.
        List<String> names = new ArrayList<>();
        for (Scored scored : rank(question, catalog)) {
            if (scored.score() <= 0 || names.size() >= Math.max(1, limit)) {
                break;
            }
            if (!names.contains(scored.tool().toolName())) {
                names.add(scored.tool().toolName());
            }
        }
        List<McpToolCandidate> result = new ArrayList<>();
        for (String name : names) {
            catalog.stream().filter(tool -> tool.toolName().equals(name)).forEach(result::add);
        }
        return result;
    }

    public static long distinctToolCount(List<McpToolCandidate> tools) {
        return tools.stream().map(McpToolCandidate::toolName).distinct().count();
    }

    public static List<Scored> rank(String question, List<McpToolCandidate> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = expand(tokens(question));
        List<Scored> scored = new ArrayList<>();

        for (McpToolCandidate tool : catalog) {
            scored.add(new Scored(tool, score(queryTokens, tool)));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.tool().toolName())
                .thenComparing(s -> s.tool().serverId()));
        return scored;
    }

    private static double score(Set<String> queryTokens, McpToolCandidate tool) {
        Set<String> nameTokens = tokens(tool.toolName() + " " + tool.title());
        Set<String> descriptionTokens = tokens(tool.description());

        double score = 0;
        for (String token : queryTokens) {
            if (nameTokens.contains(token)) {
                score += 3;
            } else if (containsPrefix(nameTokens, token)) {
                score += 2;
            }
            if (descriptionTokens.contains(token)) {
                score += 1;
            } else if (containsPrefix(descriptionTokens, token)) {
                score += 0.5;
            }
        }

        // Slightly prefer tools that need no arguments: they cannot fail on bad arguments.
        if (score > 0 && requiredCount(tool) == 0) {
            score += 0.25;
        }
        return score;
    }

    private static boolean containsPrefix(Set<String> haystack, String token) {
        if (token.length() < 4) {
            return false;
        }
        for (String candidate : haystack) {
            if (candidate.length() >= 4 && (candidate.startsWith(token) || token.startsWith(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesWord(Set<String> words, String token) {
        if (words.contains(token) || containsPrefix(words, token)) {
            return true;
        }
        String normalized = singularForm(token);
        return words.stream().anyMatch(word -> singularForm(word).equals(normalized));
    }

    /** Handles common English plurals where a prefix check is insufficient (repository/repositories). */
    private static String singularForm(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if ((word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")
                || word.endsWith("zes") || word.endsWith("sses")) && word.length() > 4) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static int requiredCount(McpToolCandidate tool) {
        Object required = tool.inputSchema().get("required");
        return required instanceof List<?> list ? list.size() : 0;
    }

    static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        // Split camelCase and snake_case identifiers as well as prose.
        String normalized = text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (!token.isBlank() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Set<String> expand(Set<String> tokens) {
        Set<String> expanded = new HashSet<>(tokens);
        for (String token : tokens) {
            expanded.addAll(SYNONYMS.getOrDefault(token, List.of()));
        }
        return expanded;
    }
}
