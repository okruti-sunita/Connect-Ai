package com.connectai.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Conservative lexical relevance check for deterministic fallback answers. */
public final class EvidenceRelevance {
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "is", "are",
            "be", "it", "this", "that", "what", "which", "who", "how", "find", "search", "list", "show",
            "get", "fetch", "read", "recent", "latest", "most", "five", "messages", "message", "mention",
            "mentioning", "slack", "public", "channel", "channels", "please", "me", "my", "from", "about");

    private EvidenceRelevance() { }

    public static boolean hasRelevantEvidence(String question, List<GroundedEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return false;
        Set<String> terms = terms(question);
        if (terms.isEmpty()) return true;
        return evidence.stream().anyMatch(item -> item.completeness() == EvidenceCompleteness.EMPTY
                || terms.stream().anyMatch(term -> terms(item.content()).contains(term)));
    }

    public static boolean allExplicitlyEmpty(List<GroundedEvidence> evidence) {
        return evidence != null && !evidence.isEmpty()
                && evidence.stream().allMatch(item -> item.completeness() == EvidenceCompleteness.EMPTY);
    }

    private static Set<String> terms(String value) {
        if (value == null) return Set.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 3 && !FUNCTION_WORDS.contains(token))
                .map(EvidenceRelevance::singular)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) return word.substring(0, word.length() - 1);
        return word;
    }
}
