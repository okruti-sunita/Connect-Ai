package com.connectai.agent;

import com.connectai.llm.LlmClient;
import com.connectai.llm.LlmGroundedAnswerGenerator;
import com.connectai.llm.GroundingValidator;
import com.connectai.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerFailureEvidenceFallbackTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static AgentExecutionState successful(String question, String tool, String text) {
        var plan = new ToolSelectionPlan(SERVER, tool, Map.of(), "read-only lookup");
        return AgentExecutionState.initial(question).addStep(new AgentExecutionStep(1, plan,
                new ToolExecutionResult(tool, false, List.of(new ToolExecutionContent("text", text)), null),
                AgentStepStatus.SUCCESS));
    }

    private static LlmGroundedAnswerGenerator generator(AtomicInteger calls) {
        GroundedAnswerGenerator fallback = new TemplateGroundedAnswerGenerator(new GroundedAnswerContextBuilder());
        LlmClient neverCalled = new LlmClient() {
            @Override public JsonNode callTool(String system, String user, ToolSpec spec) {
                calls.incrementAndGet(); throw new AssertionError("Answer model must not be called after planner failure or irrelevant evidence");
            }
            @Override public String complete(String system, String user) {
                calls.incrementAndGet(); throw new AssertionError("Answer model must not be called");
            }
        };
        return new LlmGroundedAnswerGenerator(neverCalled, new GroundedAnswerContextBuilder(),
                new GroundingValidator(), fallback);
    }

    @Test
    void groundedEvidenceSurvivesLaterPlannerFailureWithoutAnotherModelCall() {
        AtomicInteger calls = new AtomicInteger();
        AgentExecutionState state = successful("What is the octo login?", "get_me", "{\"login\":\"octo\"}")
                .finish(AgentExecutionStatus.FAILED, "LLM provider rate limit (HTTP 429). Retry after 17 seconds.",
                        AgentFailureCategory.PROVIDER_RATE_LIMIT);

        GroundedAnswer answer = generator(calls).generate(state,
                new AnswerHints(List.of("unsupported capability"), Map.of(), List.of()));

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.evidence()).hasSize(1);
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.PARTIAL);
        assertThat(answer.answer()).contains("octo", "429").doesNotContain("No connected tool provides");
        assertThat(calls).hasValue(0);
    }

    @Test
    void successfulButIrrelevantEvidenceIsRetainedAndDoesNotClaimNoToolSupportsRequest() {
        AgentExecutionState state = successful("Find the deployment incident", "search_messages", "Team lunch is at noon.")
                .finish(AgentExecutionStatus.FAILED, "LLM provider rate limit (HTTP 429).",
                        AgentFailureCategory.PROVIDER_RATE_LIMIT);
        GroundedAnswer answer = generator(new AtomicInteger()).generate(state,
                new AnswerHints(List.of("deployment incident"), Map.of(), List.of()));

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.NONE);
        assertThat(answer.evidence()).hasSize(1);
        assertThat(answer.answer()).contains("does not address the requested topic")
                .doesNotContain("No connected tool provides");
    }

    @Test
    void explicitEmptySearchIsGroundedFullAnswerWhenExecutionCompleted() {
        AgentExecutionState state = successful("Search Slack messages for an exact phrase", "search_messages",
                "No results found for this query.").finish(AgentExecutionStatus.COMPLETED, "done");
        GroundedAnswer answer = new TemplateGroundedAnswerGenerator(new GroundedAnswerContextBuilder()).generate(state);

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.coverage()).isEqualTo(GroundedAnswer.Coverage.FULL);
        assertThat(answer.answer()).contains("no matching records");
        assertThat(answer.evidence()).hasSize(1);
    }
}
