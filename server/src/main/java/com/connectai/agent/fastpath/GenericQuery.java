package com.connectai.agent.fastpath;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool-independent reading of a question: what kind of answer it wants, which words could name
 * the entity ("tickets", "channels", "deployments"...) and which concrete values it contains.
 * Matching those words and values against the live tool catalog is {@link GenericToolMatcher}'s job,
 * so nothing here knows about any particular MCP server.
 */
public record GenericQuery(
        Kind kind,
        List<String> entityWords,
        String owner,
        String repo,
        String itemKey,
        Long itemNumber,
        String quotedText,
        String channel,
        String state,
        Integer limit,
        boolean firstPerson) {

    public enum Kind {
        /** "who am I", "my account". */
        IDENTITY,
        /** "list / show / which ... <entities>". */
        LIST,
        /** One item by its reference: "PAY-123", "issue #42 in octo/api". */
        GET
    }

    private static final Pattern OWNER_REPO = Pattern.compile(
            "(?<![\\w./-])([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})(?![\\w/])");
    private static final Pattern ITEM_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d{1,7})\\b");
    private static final Pattern ITEM_NUMBER = Pattern.compile("(?:#|\\b(?:number|no\\.?|id)\\s*)(\\d{1,9})\\b");
    private static final Pattern QUOTED = Pattern.compile("[\"“']([^\"”']{2,80})[\"”']");
    private static final Pattern CHANNEL = Pattern.compile("(?<![\\w&])#([a-z][a-z0-9._-]{1,79})\\b");
    private static final Pattern LIMIT = Pattern.compile(
            "\\b(?:last|latest|recent|top|first)\\s+(\\d{1,3})\\b|\\b(\\d{1,3})\\s+(?:latest|recent|last)\\b");
    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(my|mine|i|me|own|owned|assigned to me)\\b");
    private static final Pattern ACCOUNT = Pattern.compile(
            "\\b(who am i|whoami|my (account|login|username|user name|profile|identity)|signed in as|logged in as|authenticated (user|account))\\b");
    private static final Pattern LIST_VERB = Pattern.compile(
            "\\b(list|show|display|get|fetch|find|what are|which|what|give me|see|view|any|how many|count)\\b");

    /** Words that never name an entity. */
    private static final Set<String> NOT_ENTITY = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "is", "are", "be", "do",
            "does", "did", "i", "me", "my", "mine", "own", "owned", "you", "your", "what", "which", "who", "how",
            "many", "much", "list", "show", "display", "get", "fetch", "find", "give", "see", "view", "any", "all",
            "there", "here", "this", "that", "these", "those", "please", "can", "could", "would", "latest", "last",
            "recent", "top", "first", "open", "opened", "closed", "merged", "pending", "active", "use", "using",
            "available", "tool", "tools", "mcp", "real", "evidence", "invent", "results", "report", "about", "from",
            "at", "as", "it", "its", "number", "count", "assigned", "currently", "now", "today", "current");

    public static Optional<GenericQuery> parse(String question) {
        if (question == null || question.isBlank() || question.length() > 300 || IntentParser.needsReasoning(question)) {
            return Optional.empty();
        }
        String original = question.trim();
        String lower = original.toLowerCase(Locale.ROOT);
        boolean firstPerson = FIRST_PERSON.matcher(lower).find();

        String owner = null;
        String repo = null;
        Matcher slug = OWNER_REPO.matcher(original);
        if (slug.find()) {
            owner = slug.group(1);
            repo = slug.group(2).replaceAll("[.?!,;:]+$", "");
            if (slug.find()) {
                return Optional.empty(); // two scopes: a comparison
            }
        }
        String key = find(ITEM_KEY, original);
        String number = find(ITEM_NUMBER, lower);
        String quoted = find(QUOTED, original);
        String channel = find(CHANNEL, lower);
        Integer limit = limit(lower);

        // Entity candidates: content words left after removing values and function words.
        String residue = lower;
        for (String value : new String[]{owner == null ? null : owner + "/" + repo, key, quoted, channel == null ? null : "#" + channel}) {
            if (value != null) residue = residue.replace(value.toLowerCase(Locale.ROOT), " ");
        }
        Set<String> words = new LinkedHashSet<>();
        for (String token : residue.split("[^a-z0-9_-]+")) {
            if (token.length() >= 3 && !NOT_ENTITY.contains(token) && !token.chars().allMatch(Character::isDigit)) {
                words.add(token);
            }
        }

        Kind kind;
        if (ACCOUNT.matcher(lower).find() && words.stream().allMatch(w -> Set.of("account", "login", "username",
                "profile", "identity", "authenticated", "user", "signed", "logged", "github", "identify").contains(w))) {
            kind = Kind.IDENTITY;
        } else if (key != null || number != null) {
            kind = Kind.GET;
        } else if (LIST_VERB.matcher(lower).find() || firstPerson) {
            kind = Kind.LIST;
        } else {
            return Optional.empty();
        }
        if (kind == Kind.LIST && words.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GenericQuery(kind, new ArrayList<>(words), owner, repo, key,
                number == null ? null : Long.parseLong(number), quoted, channel, state(lower), limit, firstPerson));
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String state(String q) {
        if (q.matches(".*\\bmerged\\b.*")) return "merged";
        if (q.matches(".*\\bclosed\\b.*")) return "closed";
        if (q.matches(".*\\b(open|opened|pending|active)\\b.*")) return "open";
        return null;
    }

    private static Integer limit(String q) {
        Matcher matcher = LIMIT.matcher(q);
        if (!matcher.find()) return null;
        int value = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        return Math.max(1, Math.min(value, 50));
    }
}
