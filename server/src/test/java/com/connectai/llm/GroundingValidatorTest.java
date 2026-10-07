package com.connectai.llm;

import com.connectai.agent.GroundedEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingValidatorTest {

    private static final GroundedEvidence E1 = new GroundedEvidence(
            "E1", 1, UUID.randomUUID(), "search_jira", "PAY-123 was reopened.");

    @Test
    void acceptsKnownCitation() {
        GroundingValidation result = new GroundingValidator()
                .validate("PAY-123 was reopened. [E1]", List.of(E1));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsUnknownCitation() {
        GroundingValidation result = new GroundingValidator()
                .validate("The deployment failed. [E9]", List.of(E1));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("unknown citation");
    }

    @Test
    void rejectsAnswerWithoutCitation() {
        GroundingValidation result = new GroundingValidator()
                .validate("The deployment failed.", List.of(E1));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("no evidence citation");
    }

    @Test
    void rejectsBlankAnswer() {
        GroundingValidation result = new GroundingValidator()
                .validate(" ", List.of(E1));

        assertThat(result.valid()).isFalse();
    }
}
