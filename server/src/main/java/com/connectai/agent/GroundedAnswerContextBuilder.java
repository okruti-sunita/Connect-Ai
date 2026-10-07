package com.connectai.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts internal execution state into a bounded, user-safe evidence context.
 */
@Component
public class GroundedAnswerContextBuilder {

    static final int MAX_EVIDENCE_ITEMS = 24;
    static final int MAX_CONTENT_CHARS = 4000;
    static final int MAX_TOTAL_EVIDENCE_CHARS = 24000;

    public GroundedAnswerContext build(AgentExecutionState state) {
        Objects.requireNonNull(state, "state");

        List<GroundedEvidence> evidence = new ArrayList<>();
        List<String> failedTools = new ArrayList<>();
        int totalChars = 0;

        for (AgentExecutionStep step : state.steps()) {
            if (step.status() == AgentStepStatus.FAILED || step.result().error()) {
                failedTools.add(step.plan().toolName());
                continue;
            }

            if (evidence.size() >= MAX_EVIDENCE_ITEMS || totalChars >= MAX_TOTAL_EVIDENCE_CHARS) {
                break;
            }

            String content = renderResult(step.result());
            if (content.isBlank()) {
                continue;
            }

            int remainingChars = MAX_TOTAL_EVIDENCE_CHARS - totalChars;
            String boundedContent = truncate(content, Math.min(MAX_CONTENT_CHARS, remainingChars));
            if (boundedContent.isBlank()) {
                continue;
            }

            evidence.add(new GroundedEvidence(
                    "E" + (evidence.size() + 1),
                    step.stepNumber(),
                    step.plan().serverId(),
                    step.plan().toolName(),
                    boundedContent));
            totalChars += boundedContent.length();
        }

        return new GroundedAnswerContext(
                state.question(),
                state.status(),
                evidence,
                failedTools);
    }

    private static String renderResult(ToolExecutionResult result) {
        StringBuilder text = new StringBuilder();

        if (result.content() != null) {
            result.content().forEach(content -> {
                if (content == null) {
                    return;
                }
                if (content.text() != null && !content.text().isBlank()) {
                    if (!text.isEmpty()) {
                        text.append("\n");
                    }
                    text.append("[")
                            .append(content.type() == null ? "content" : content.type())
                            .append("] ")
                            .append(content.text());
                }
            });
        }

        if (result.structuredContent() != null) {
            if (!text.isEmpty()) {
                text.append("\n");
            }
            text.append("[structured] ")
                    .append(String.valueOf(result.structuredContent()));
        }

        return text.toString().trim();
    }

    private static String truncate(String value, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }
}
