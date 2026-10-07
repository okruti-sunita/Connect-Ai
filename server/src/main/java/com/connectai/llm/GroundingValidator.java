package com.connectai.llm;

import com.connectai.agent.GroundedEvidence;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap deterministic guardrail for final answers.
 *
 * It verifies that the model cites evidence that actually exists. It does not
 * attempt to prove semantic truth; the evidence itself remains the source of truth.
 */
@Component
public class GroundingValidator {

    private static final Pattern CITATION =
            Pattern.compile("\\[(E\\d+)]");

    public GroundingValidation validate(
            String answer,
            List<GroundedEvidence> evidence) {

        if (answer == null || answer.isBlank()) {
            return new GroundingValidation(false, "answer is blank");
        }

        Objects.requireNonNull(evidence, "evidence");

        Set<String> allowed = new HashSet<>();
        evidence.forEach(item -> allowed.add(item.citation()));

        Matcher matcher = CITATION.matcher(answer);
        boolean hasCitation = false;

        while (matcher.find()) {
            hasCitation = true;
            String citation = matcher.group(1);

            if (!allowed.contains(citation)) {
                return new GroundingValidation(
                        false,
                        "answer contains unknown citation: " + citation);
            }
        }

        if (!hasCitation) {
            return new GroundingValidation(
                    false,
                    "answer contains no evidence citation");
        }

        return new GroundingValidation(true, "grounded");
    }
}
