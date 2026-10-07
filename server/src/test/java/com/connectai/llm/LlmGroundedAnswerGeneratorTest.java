package com.connectai.llm;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.GroundedAnswerGenerator;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSelectionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGroundedAnswerGeneratorTest {

    private static final UUID SERVER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void acceptsLlmAnswerWhenItCitesKnownEvidence() {
        LlmClient llm = new StubLlmClient("PAY-123 was reopened. [E1]");
        GroundedAnswerGenerator fallback =
                new com.connectai.agent.TemplateGroundedAnswerGenerator(
                        new GroundedAnswerContextBuilder());

        GroundedAnswer answer = new LlmGroundedAnswerGenerator(
                llm,
                new GroundedAnswerContextBuilder(),
                new GroundingValidator(),
                fallback).generate(state());

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.fallbackUsed()).isFalse();
        assertThat(answer.answer()).contains("[E1]");
    }

    @Test
    void rejectsUnknownCitationAndUsesFallback() {
        LlmClient llm = new StubLlmClient("The ticket failed. [E99]");
        GroundedAnswerGenerator fallback =
                new com.connectai.agent.TemplateGroundedAnswerGenerator(
                        new GroundedAnswerContextBuilder());

        GroundedAnswer answer = new LlmGroundedAnswerGenerator(
                llm,
                new GroundedAnswerContextBuilder(),
                new GroundingValidator(),
                fallback).generate(state());

        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.answer()).contains("[E1]");
    }

    @Test
    void usesFallbackWhenLlmThrows() {
        LlmClient llm = new StubLlmClient(null, true);
        GroundedAnswerGenerator fallback =
                new com.connectai.agent.TemplateGroundedAnswerGenerator(
                        new GroundedAnswerContextBuilder());

        GroundedAnswer answer = new LlmGroundedAnswerGenerator(
                llm,
                new GroundedAnswerContextBuilder(),
                new GroundingValidator(),
                fallback).generate(state());

        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.answer()).contains("[E1]");
    }

    private static AgentExecutionState state() {
        ToolSelectionPlan plan = new ToolSelectionPlan(
                SERVER_ID,
                "search_jira",
                Map.of("query", "PAY-123"),
                "ticket lookup");

        return AgentExecutionState.initial("Why did payment fail?")
                .addStep(new AgentExecutionStep(
                        1,
                        plan,
                        new ToolExecutionResult(
                                "search_jira",
                                false,
                                List.of(new ToolExecutionContent(
                                        "text",
                                        "Ticket PAY-123 was reopened.")),
                                null),
                        AgentStepStatus.SUCCESS));
    }

    private static final class StubLlmClient implements LlmClient {
        private final String answer;
        private final boolean fail;

        private StubLlmClient(String answer) {
            this(answer, false);
        }

        private StubLlmClient(String answer, boolean fail) {
            this.answer = answer;
            this.fail = fail;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode callTool(
                String system,
                String user,
                ToolSpec tool) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public String complete(String system, String user) {
            if (fail) {
                throw new LlmException("simulated LLM failure");
            }
            return answer;
        }
    }
}
