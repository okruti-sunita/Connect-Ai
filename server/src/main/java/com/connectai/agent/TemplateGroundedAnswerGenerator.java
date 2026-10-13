package com.connectai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic fallback answer generator. It never invents facts: it either reports
 * why no evidence was gathered, or lists the evidence verbatim with its citations.
 */
@Component
public class TemplateGroundedAnswerGenerator implements GroundedAnswerGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SUMMARY_FACTS = 12;

    private final GroundedAnswerContextBuilder contextBuilder;

    public TemplateGroundedAnswerGenerator(GroundedAnswerContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public GroundedAnswer generate(AgentExecutionState state) {
        return generate(state, AnswerHints.NONE);
    }

    @Override
    public GroundedAnswer generate(AgentExecutionState state, AnswerHints hints) {
        GroundedAnswerContext context = contextBuilder.build(state);
        AnswerHints runHints = hints == null ? AnswerHints.NONE : hints;
        List<String> limitations = new ArrayList<>(context.qualityWarnings());
        limitations.addAll(runHints.warnings());
        if (!context.failureDetails().isEmpty()) {
            limitations.add("Some tool calls failed: " + String.join("; ", context.failureDetails()));
        }
        if (!runHints.uncoveredCapabilities().isEmpty()) {
            limitations.add("No connected tool provides data about "
                    + String.join(", ", runHints.uncoveredCapabilities()));
        }
        limitations = limitations.stream().distinct().toList();

        if (!context.hasEvidence()) {
            StringBuilder answer = new StringBuilder(
                    "I could not establish a reliable answer from the available tool results.");
            if (!context.failureDetails().isEmpty()) {
                answer.append(" Failed steps: ").append(String.join("; ", context.failureDetails())).append('.');
            } else if (context.failedTools().isEmpty()) {
                answer.append(" No tool returned usable evidence.");
            }
            if (context.stopReason() != null && !context.stopReason().isBlank()) {
                answer.append(" Reason: ").append(context.stopReason());
            }
            appendLimitations(answer, limitations);

            return new GroundedAnswer(
                    answer.toString().trim(),
                    context.evidence(),
                    context.executionStatus(),
                    false,
                    true);
        }

        String answer = context.evidence().stream()
                .map(evidence -> "[" + evidence.citation() + "] " + evidence.toolName() + ": "
                        + readableEvidence(evidence.content()))
                .collect(Collectors.joining("\n"));

        StringBuilder finalAnswer = new StringBuilder("The available evidence reports:\n").append(answer);
        appendLimitations(finalAnswer, limitations);
        return new GroundedAnswer(
                finalAnswer.toString(),
                context.evidence(),
                context.executionStatus(),
                true,
                true);
    }

    private static void appendLimitations(StringBuilder answer, List<String> limitations) {
        if (!limitations.isEmpty()) {
            answer.append("\n\nLimitations: ").append(String.join("; ", limitations)).append('.');
        }
    }

    private static String readableEvidence(String evidence) {
        int contentStart = evidence.indexOf("] ");
        String raw = contentStart >= 0 ? evidence.substring(contentStart + 2) : evidence;
        int nextBlock = raw.indexOf("\n[");
        if (nextBlock >= 0) raw = raw.substring(0, nextBlock);
        try {
            JsonNode root = MAPPER.readTree(raw.trim());
            List<String> facts = new ArrayList<>();
            collectFacts(root, "", facts);
            if (!facts.isEmpty()) {
                return String.join("; ", facts);
            }
        } catch (Exception ignored) {
            // Plain text or non-JSON tool output remains verbatim evidence.
        }
        return evidence;
    }

    private static void collectFacts(JsonNode node, String path, List<String> facts) {
        if (facts.size() >= MAX_SUMMARY_FACTS) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext() && facts.size() < MAX_SUMMARY_FACTS) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (key.equalsIgnoreCase("id") || key.endsWith("_url") && !key.equals("html_url")) continue;
                collectFacts(field.getValue(), path.isEmpty() ? humanize(key) : path + " " + humanize(key), facts);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size() && facts.size() < MAX_SUMMARY_FACTS; i++) {
                collectFacts(node.get(i), path.isEmpty() ? "item " + (i + 1) : path + " item " + (i + 1), facts);
            }
        } else if (!node.isNull()) {
            String value = node.isTextual() ? node.asText() : node.toString();
            facts.add(path + ": " + value);
        }
    }

    private static String humanize(String key) {
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toLowerCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
