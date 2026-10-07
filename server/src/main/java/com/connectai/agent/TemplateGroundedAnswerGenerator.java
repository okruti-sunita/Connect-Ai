package com.connectai.agent;

import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Deterministic fallback answer generator. It never invents facts.
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
            String failureDetails = context.failedTools().isEmpty()
                    ? "No tool returned usable evidence."
                    : "The following tools failed: "
                    + String.join(", ", context.failedTools()) + ".";

            return new GroundedAnswer(
                    "I could not establish a reliable answer from the available tool results. "
                            + failureDetails,
                    context.evidence(),
                    context.executionStatus(),
                    false,
                    true);
        }

        String answer = context.evidence().stream()
                .map(evidence -> "[" + evidence.citation() + "] " + evidence.content())
                .collect(Collectors.joining("\n"));

        return new GroundedAnswer(
                "The available evidence is:\n" + answer,
                context.evidence(),
                context.executionStatus(),
                true,
                true);
    }
}
