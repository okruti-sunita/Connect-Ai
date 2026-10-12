package com.connectai.llm;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswer.Coverage;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.TemplateGroundedAnswerGenerator;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.llm.LlmMcpToolPlannerRobustnessTest.SequenceLlm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Scripted (mock) model replies; no real model involved. */
class LlmGroundedAnswerGeneratorTest {

    private static final UUID SERVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static AgentExecutionState state(String tool, Map<String, Object> args, String output) {
        return AgentExecutionState.initial("question").addStep(new AgentExecutionStep(1,
                new ToolSelectionPlan(SERVER_ID, tool, args, "r"),
                new ToolExecutionResult(tool, false, List.of(new ToolExecutionContent("text", output)), null),
                AgentStepStatus.SUCCESS)).finish(AgentExecutionStatus.COMPLETED, "done");
    }

    private static GroundedAnswer generate(LlmClient llm, AgentExecutionState state) {
        GroundedAnswerContextBuilder builder = new GroundedAnswerContextBuilder();
        return new LlmGroundedAnswerGenerator(llm, builder, new GroundingValidator(),
                new TemplateGroundedAnswerGenerator(builder)).generate(state);
    }

    private static final AgentExecutionState GET_ME = state("get_me", Map.of(), "{\"login\":\"octo-user\",\"id\":7}");

    @Test
    void acceptsAFullySupportedAnswer() {
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":\"Your login is `octo-user`.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, GET_ME);

        assertThat(answer.fallbackUsed()).isFalse();
        assertThat(answer.coverage()).isEqualTo(Coverage.FULL);
        assertThat(answer.answer()).contains("octo-user").contains("[E1]");
        assertThat(llm.specs.get(0).inputSchema().toString()).contains("E1");
    }

    @Test
    void rejectsValuesThatAreNotInTheEvidenceThenAcceptsTheCorrection() {
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":\"Your login is `someone-else` with 12 repos.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}")
                .then("{\"answer\":\"Your login is `octo-user`.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, GET_ME);

        assertThat(answer.coverage()).isEqualTo(Coverage.FULL);
        assertThat(llm.prompts.get(1)).contains("do not appear in the evidence").contains("someone-else").contains("12");
    }

    /** The reported failure: a keyword search over public repos was presented as the user's CI failures. */
    @Test
    void relatedButDifferentEvidenceIsReportedAsNotAnswered() {
        AgentExecutionState search = state("search_repositories", Map.of("query", "github action failures"),
                "{\"total_count\":534,\"items\":[{\"name\":\"local-ci\",\"open_issues_count\":9}]}");
        SequenceLlm llm = new SequenceLlm().then("{\"answer\":\"This cannot be answered from the evidence: it is a keyword "
                + "search of public repositories, not workflow run results.\",\"coverage\":\"none\","
                + "\"missing\":\"GitHub Actions workflow run results for your repositories\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, search);

        assertThat(answer.coverage()).isEqualTo(Coverage.NONE);
        assertThat(answer.grounded()).isFalse();
        assertThat(answer.answer()).contains("cannot be answered").contains("Not covered by the evidence");
        assertThat(llm.prompts.get(0)).contains("github action failures"); // the model sees how evidence was obtained
    }

    @Test
    void answerClaimingCoverageWithoutCitationIsRejected() {
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":\"Your login is octo-user.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[]}")
                .then("{\"answer\":\"Your login is octo-user.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[]}");

        GroundedAnswer answer = generate(llm, GET_ME);

        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.coverage()).isEqualTo(Coverage.PARTIAL);
    }

    @Test
    void modelFailureFallsBackToEvidenceListingMarkedPartial() {
        SequenceLlm llm = new SequenceLlm().thenFail("Ollama did not answer within 180s");

        GroundedAnswer answer = generate(llm, GET_ME);

        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(answer.answer()).contains("[E1]").contains("octo-user");
    }

    @Test
    void noEvidenceNeverCallsTheModel() {
        SequenceLlm llm = new SequenceLlm();
        GroundedAnswer answer = generate(llm, AgentExecutionState.initial("q").finish(AgentExecutionStatus.COMPLETED, "none"));

        assertThat(llm.prompts).isEmpty();
        assertThat(answer.coverage()).isEqualTo(Coverage.NONE);
    }

    /** Reported case: only one of two connections was queried, yet the answer spoke for "each" connection. */
    @Test
    void answerAboutEveryConnectionIsCappedWhenSomeWereNotQueried() {
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        SequenceLlm llm = new SequenceLlm().then("{\"answer\":\"The login of each connection is `octo-user`.\","
                + "\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");
        GroundedAnswerContextBuilder builder = new GroundedAnswerContextBuilder();
        AgentExecutionState state = AgentExecutionState.initial("Report the login of each of my connections")
                .addStep(GET_ME.steps().get(0)).finish(AgentExecutionStatus.COMPLETED, "done");

        GroundedAnswer answer = new LlmGroundedAnswerGenerator(llm, builder, new GroundingValidator(),
                new TemplateGroundedAnswerGenerator(builder)).generate(state,
                new com.connectai.agent.AnswerHints(List.of(), Map.of(SERVER_ID, "GitHub - Main", other, "GitHub - Work")));

        assertThat(answer.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(answer.answer()).contains("not queried: GitHub - Work");
        assertThat(llm.prompts.get(0)).contains("connection=\"GitHub - Main\"").contains("NOT queried in this run: GitHub - Work");
    }

    /** Reported case: "The latest commits ... are as follows: [E1]" with nothing listed. */
    @Test
    void emptyListAnswerIsRepaired() {
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":\"Your account is as follows: [E1]\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}")
                .then("{\"answer\":\"Your login is `octo-user`.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, GET_ME);

        assertThat(answer.answer()).contains("octo-user");
        assertThat(llm.prompts.get(1)).contains("announces a list but contains none");
    }

    /** Reported case: "not directly listed in the provided evidence" was labelled full coverage. */
    @Test
    void answerThatAdmitsMissingDataIsNotFull() {
        SequenceLlm llm = new SequenceLlm().then("{\"answer\":\"The repositories of `octo-user` are not directly listed "
                + "in the provided evidence.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        assertThat(generate(llm, GET_ME).coverage()).isEqualTo(Coverage.PARTIAL);
    }

    @Test
    void claimCheckFindsUnsupportedIdentifiersAndNumbers() {
        var evidence = List.of(new com.connectai.agent.GroundedEvidence("E1", 1, SERVER_ID, "t", "{\"name\":\"api\",\"count\":34}"));

        assertThat(ClaimSupportCheck.unsupported("`api` has 34 runs [E1]", evidence)).isEmpty();
        assertThat(ClaimSupportCheck.unsupported("`web` has 3 runs [E1]", evidence)).containsExactly("web", "3");
        // single-quoted values are checked; apostrophes are not quotes
        assertThat(ClaimSupportCheck.unsupported("The user's repo is 'web' [E1]", evidence)).containsExactly("web");
        assertThat(ClaimSupportCheck.unsupported("The user's repo is 'api' [E1]", evidence)).isEmpty();
    }
}
