package com.connectai.agent.learning;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A question with its concrete values replaced by named slots, so that
 * "summarize the commits of okruti/awsdemo1" and "summarize the commits of acme/api"
 * share the template "summarize the commits of {owner}/{repo}".
 *
 * Slots: {owner}/{repo}, {key} (PAY-123), {number} (#42), {text} ("quoted"), {channel} (#general),
 * {n} (any other number). Everything else must match word for word (case and punctuation ignored).
 */
public record QuestionTemplate(String template, Map<String, String> slots) {

    private static final Pattern OWNER_REPO = Pattern.compile(
            "(?<![\\w./-])([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})(?![\\w/])");
    private static final Pattern KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d{1,7})\\b");
    private static final Pattern QUOTED = Pattern.compile("[\"“]([^\"”]{2,80})[\"”]");
    private static final Pattern NUMBER_REF = Pattern.compile("#(\\d{1,9})\\b");
    private static final Pattern CHANNEL = Pattern.compile("(?<![\\w&])#([a-z][a-z0-9._-]{1,79})\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,6})\\b");

    public static QuestionTemplate of(String question) {
        String text = question == null ? "" : question.trim();
        Map<String, String> slots = new LinkedHashMap<>();

        text = replaceFirst(text, QUOTED, " {text} ", slots, "text");
        Matcher slug = OWNER_REPO.matcher(text);
        if (slug.find()) {
            slots.put("owner", slug.group(1));
            slots.put("repo", slug.group(2).replaceAll("[.?!,;:]+$", ""));
            text = text.substring(0, slug.start()) + " {owner}/{repo} " + text.substring(slug.end());
        }
        text = replaceFirst(text, KEY, " {key} ", slots, "key");
        text = replaceFirst(text, NUMBER_REF, " #{number} ", slots, "number");
        text = replaceFirst(text, CHANNEL, " #{channel} ", slots, "channel");
        text = replaceFirst(text, NUMBER, " {n} ", slots, "n");

        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9{}#/ ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return new QuestionTemplate(normalized, slots);
    }

    private static String replaceFirst(String text, Pattern pattern, String placeholder,
                                       Map<String, String> slots, String slot) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        slots.put(slot, matcher.group(1));
        return text.substring(0, matcher.start()) + placeholder + text.substring(matcher.end());
    }
}
