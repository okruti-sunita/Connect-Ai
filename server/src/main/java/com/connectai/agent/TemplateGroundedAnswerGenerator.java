package com.connectai.agent;

import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Deterministic fallback answer generator. It never invents facts: it either reports
 * why no evidence was gathered, or lists the evidence verbatim with its citations.
 */
@Component
public class TemplateGroundedAnswerGenerator implements GroundedAnswerGenerator {

    private final GroundedAnswerContextBuilder contextBuilder;

    public TemplateGroundedAnswerGenerator(GroundedAnswerContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public GroundedAnswer generate(AgentExecutionState state) {
        GroundedAnswerContext context = contextBuilder.build(state);

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

            return new GroundedAnswer(
                    answer.toString().trim(),
                    context.evidence(),
                    context.executionStatus(),
                    false,
                    true);
        }

        String answer = context.evidence().stream()
                .map(evidence -> "[" + evidence.citation() + "] " + evidence.toolName() + ": " + evidence.content())
                .collect(Collectors.joining("\n"));

        return new GroundedAnswer(
                "The available evidence is:\n" + answer,
                context.evidence(),
                context.executionStatus(),
                true,
                true);
    }
}
