package com.connectai.agent.fastpath;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parser for the questions engineers ask most often. When it recognises one, the
 * fast path answers it with a fixed recipe of read-only tool calls and a template answer - no
 * model call at all. It is deliberately conservative: anything that needs judgement (why,
 * compare, most, root cause, failures...) is left to the AI agent.
 */
public final class IntentParser {

    public enum Intent {
        WHO_AM_I,
        MY_REPOS,
        MY_PULL_REQUESTS,
        REPO_COMMITS,
        REPO_PULL_REQUESTS,
        REPO_ISSUES,
        REPO_RELEASES,
        REPO_LATEST_RELEASE,
        REPO_BRANCHES
    }

    /**
     * @param state  "open", "closed", "merged" or "all" (null when the question does not say)
     * @param limit  how many items were asked for (null when not stated)
     */
    public record ParsedQuestion(Intent intent, String owner, String repo, String state, Integer limit) {
    }

    /** Questions that need reasoning, ranking or interpretation are never answered by a template. */
    // Judgement, ranking, causation and time windows. Failure words stay here too: no tool filter can
    // be assumed to express "failed", so those go to the agent (and its topic gate).
    // Nouns like "deployments" or "incidents" are lookups, not judgement, and are not listed.
    private static final Pattern NEEDS_REASONING = Pattern.compile(
            "\\b(why|how come|explain|cause[sd]?|root|compare|comparison|versus|vs\\.?|most|least|best|worst|"
                    + "should|recommend|summari[sz]e|analy[sz]e|trend|fail(ure|ures|ed|ing)?|broken|"
                    + "risk|impact|related|similar|before|after|since|between|which of)\\b");

    private static final Pattern OWNER_REPO = Pattern.compile(
            "(?<![\\w./-])([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})(?![\\w/])");

    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(my|mine|i|me|own|owned)\\b");
    private static final Pattern ACCOUNT_WORDS = Pattern.compile(
            "\\b(who am i|whoami|account|login|username|user name|profile|identity|signed in|logged in|authenticated)\\b");
    private static final Pattern REPO_WORDS = Pattern.compile("\\b(repo|repos|repository|repositories|projects?)\\b");
    private static final Pattern PULL_WORDS = Pattern.compile("\\b(pull requests?|prs?|merge requests?)\\b");
    private static final Pattern COMMIT_WORDS = Pattern.compile("\\b(commits?|changes|changed|history)\\b");
    private static final Pattern ISSUE_WORDS = Pattern.compile("\\b(issues?|bugs?|tickets?)\\b");
    // Tags are not releases: "list tags" is served by the generic layer (list_tags), not this recipe.
    private static final Pattern RELEASE_WORDS = Pattern.compile("\\b(releases?|versions?)\\b");
    private static final Pattern BRANCH_WORDS = Pattern.compile("\\b(branch|branches)\\b");
    private static final Pattern LIMIT = Pattern.compile("\\b(?:last|latest|recent|top|first)\\s+(\\d{1,3})\\b|\\b(\\d{1,3})\\s+(?:latest|recent|last)\\b");

    private IntentParser() {
    }

    /** True when the question needs reasoning, ranking or interpretation (never answered by a template). */
    public static boolean needsReasoning(String question) {
        return question == null || NEEDS_REASONING.matcher(question.toLowerCase(Locale.ROOT)).find();
    }

    /** The word that makes a question need judgement ("why", "most"...), or null. */
    public static String reasoningWord(String question) {
        if (question == null) return null;
        Matcher matcher = NEEDS_REASONING.matcher(question.toLowerCase(Locale.ROOT));
        return matcher.find() ? matcher.group() : null;
    }

    public static Optional<ParsedQuestion> parse(String question) {
        if (question == null || question.isBlank() || question.length() > 300) {
            return Optional.empty();
        }
        String original = question.trim();
        String q = original.toLowerCase(Locale.ROOT);
        if (NEEDS_REASONING.matcher(q).find()) {
            return Optional.empty();
        }

        String state = state(q);
        Integer limit = limit(q);
        Matcher slug = OWNER_REPO.matcher(original);
        if (slug.find()) {
            String owner = slug.group(1);
            String repo = slug.group(2).replaceAll("[.?!,;:]+$", "");
            if (slug.find()) {
                return Optional.empty(); // two repositories: a comparison, leave it to the agent
            }
            return repositoryIntent(q).map(intent -> new ParsedQuestion(intent, owner, repo, state, limit));
        }

        boolean firstPerson = FIRST_PERSON.matcher(q).find();
        if (!firstPerson) {
            return Optional.empty();
        }
        if (PULL_WORDS.matcher(q).find() && !ISSUE_WORDS.matcher(q).find()) {
            return Optional.of(new ParsedQuestion(Intent.MY_PULL_REQUESTS, null, null, state, limit));
        }
        boolean otherData = COMMIT_WORDS.matcher(q).find() || ISSUE_WORDS.matcher(q).find()
                || RELEASE_WORDS.matcher(q).find() || BRANCH_WORDS.matcher(q).find();
        if (otherData) {
            return Optional.empty(); // e.g. "my commits" needs a repository: the agent asks/decides
        }
        if (REPO_WORDS.matcher(q).find()) {
            return Optional.of(new ParsedQuestion(Intent.MY_REPOS, null, null, null, limit));
        }
        if (ACCOUNT_WORDS.matcher(q).find()) {
            return Optional.of(new ParsedQuestion(Intent.WHO_AM_I, null, null, null, null));
        }
        return Optional.empty();
    }

    private static Optional<Intent> repositoryIntent(String q) {
        int matches = 0;
        Intent intent = null;
        if (PULL_WORDS.matcher(q).find()) { matches++; intent = Intent.REPO_PULL_REQUESTS; }
        if (COMMIT_WORDS.matcher(q).find()) { matches++; intent = Intent.REPO_COMMITS; }
        if (ISSUE_WORDS.matcher(q).find()) { matches++; intent = Intent.REPO_ISSUES; }
        if (BRANCH_WORDS.matcher(q).find()) { matches++; intent = Intent.REPO_BRANCHES; }
        if (RELEASE_WORDS.matcher(q).find()) {
            matches++;
            intent = q.matches(".*\\b(latest|newest|current|last) (release|version)\\b.*") && !q.matches(".*\\breleases\\b.*")
                    ? Intent.REPO_LATEST_RELEASE : Intent.REPO_RELEASES;
        }
        return matches == 1 ? Optional.of(intent) : Optional.empty(); // ambiguous mixes go to the agent
    }

    private static String state(String q) {
        if (q.matches(".*\\bmerged\\b.*")) return "merged";
        if (q.matches(".*\\bclosed\\b.*")) return "closed";
        if (q.matches(".*\\b(all|any)\\b.*")) return "all";
        if (q.matches(".*\\b(open|opened|pending|active)\\b.*")) return "open";
        return null;
    }

    private static Integer limit(String q) {
        Matcher matcher = LIMIT.matcher(q);
        if (!matcher.find()) {
            return null;
        }
        int value = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        return Math.max(1, Math.min(value, 50));
    }
}
