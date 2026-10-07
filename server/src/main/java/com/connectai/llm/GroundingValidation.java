package com.connectai.llm;

/** Result of deterministic validation of an LLM answer. */
public record GroundingValidation(
        boolean valid,
        String reason
) {
}
