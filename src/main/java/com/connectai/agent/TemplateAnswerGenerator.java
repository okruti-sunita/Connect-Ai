package com.connectai.agent;

import org.springframework.stereotype.Component;

import java.util.List;

/** Deterministic, fully grounded answer: it can only repeat what the tools returned. */
@Component
public class TemplateAnswerGenerator implements AnswerGenerator {

    @Override
    public String generate(String question, List<Finding> evidence) {
        if (evidence.isEmpty()) {
            return "I could not find evidence for this question in the connected tools, so I won't guess. "
                    + "Try mentioning a service name, a ticket key or a release version.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Based on ").append(evidence.size()).append(" piece(s) of evidence:\n");
        for (int i = 0; i < evidence.size(); i++) {
            Finding f = evidence.get(i);
            sb.append(i + 1).append(". ").append(f.summary())
                    .append(" [").append(f.source()).append(": ").append(f.sourceRef()).append("]\n");
        }
        sb.append("Every statement above is quoted from a connected tool; nothing is inferred beyond it.");
        return sb.toString();
    }
}
