package com.connectai.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic guardrail on the model's answer: it must cite evidence, and only evidence that exists.
 * (A stricter per-sentence check can be added later.)
 */
final class GroundingCheck {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})\\]");

    record Result(boolean ok, String reason) {
    }

    private GroundingCheck() {
    }

    static Result check(String answer, int evidenceCount) {
        Matcher m = CITATION.matcher(answer);
        boolean anyCitation = false;
        while (m.find()) {
            anyCitation = true;
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > evidenceCount) {
                return new Result(false, "cites [" + n + "] but only " + evidenceCount + " evidence items exist");
            }
        }
        return anyCitation ? new Result(true, "") : new Result(false, "the answer contains no citations");
    }
}
