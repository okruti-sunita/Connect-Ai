package com.connectai.llm;

import com.connectai.agent.GroundedEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap deterministic check that the specific values an answer asserts (identifiers in
 * backticks or quotes, and numbers) literally occur in the evidence. It cannot prove that
 * an answer is correct, but it catches the most common small-model failure: stating a
 * name or number the tools never returned.
 */
final class ClaimSupportCheck {

    private static final Pattern CITATION = Pattern.compile("\\[E\\d+]");
    // Backticks, double quotes, and single quotes that are not apostrophes (not preceded/followed by a letter).
    private static final Pattern QUOTED = Pattern.compile(
            "`([^`]{2,120})`|\"([^\"]{2,120})\"|(?<![\\w])'([^'\\n]{2,80})'(?![\\w])");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d[\\d,]*(?:\\.\\d+)?(?![\\w.])");

    private ClaimSupportCheck() {
    }

    /** Returns the asserted values that do not appear anywhere in the evidence. */
    static List<String> unsupported(String answer, List<GroundedEvidence> evidence) {
        String text = CITATION.matcher(answer == null ? "" : answer).replaceAll(" ");
        String haystack = String.join("\n", evidence.stream().map(GroundedEvidence::content).toList())
                .toLowerCase(Locale.ROOT);

        Set<String> claims = new LinkedHashSet<>();
        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) {
            String value = quoted.group(1) != null ? quoted.group(1)
                    : quoted.group(2) != null ? quoted.group(2) : quoted.group(3);
            claims.add(value.trim());
        }
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            claims.add(numbers.group().replace(",", ""));
        }

        List<String> missing = new ArrayList<>();
        for (String claim : claims) {
            if (claim.isBlank()) {
                continue;
            }
            String needle = claim.toLowerCase(Locale.ROOT);
            boolean found = needle.matches("[\\d.]+")
                    ? Pattern.compile("(?<![\\d.])" + Pattern.quote(needle) + "(?![\\d])").matcher(haystack).find()
                    : haystack.contains(needle);
            if (!found) {
                missing.add(claim);
            }
        }
        return missing;
    }
}
