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
        return state(tool, args, output, null);
    }

    private static AgentExecutionState state(String tool, Map<String, Object> args, String output,
                                             Map<String, Object> structuredContent) {
        return AgentExecutionState.initial("question").addStep(new AgentExecutionStep(1,
                new ToolSelectionPlan(SERVER_ID, tool, args, "r"),
                new ToolExecutionResult(tool, false, List.of(new ToolExecutionContent("text", output)), structuredContent),
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
    void summarizesGitHubStyleStructuredEvidenceAsProseAndKeepsRawEvidence() {
        String raw = "{\"total_count\":1,\"incomplete_results\":false,\"items\":[{\"name\":\"Connect-Ai\","
                + "\"full_name\":\"okruti-sunita/Connect-Ai\",\"html_url\":\"https://github.com/okruti-sunita/Connect-Ai\","
                + "\"language\":\"Java\",\"stargazers_count\":0,\"forks_count\":0,\"open_issues_count\":0,"
                + "\"updated_at\":\"2026-10-12T12:59:11Z\",\"private\":false,\"default_branch\":\"main\"}]}";
        AgentExecutionState search = state("search_repositories", Map.of("query", "user:okruti-sunita"), raw,
                Map.of("total_count", 1, "items", List.of(Map.of("name", "Connect-Ai", "full_name", "okruti-sunita/Connect-Ai",
                        "language", "Java", "default_branch", "main"))));
        SequenceLlm llm = new SequenceLlm().then("{\"answer\":\"The search found one public repository, "
                + "`okruti-sunita/Connect-Ai`, written in Java. Its default branch is `main`, with 0 stars, "
                + "0 forks, and 0 open issues.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, search);

        assertThat(answer.answer()).contains("one public repository", "okruti-sunita/Connect-Ai", "Java", "[E1]")
                .doesNotStartWith("{");
        assertThat(answer.evidence()).hasSize(1).first().satisfies(evidence ->
                assertThat(evidence.content()).contains("Connect-Ai", "stargazers_count", "default_branch", "[structured]"));
        assertThat(answer.fallbackUsed()).isFalse();
    }

    @Test
    void rawJsonModelAnswerIsRejectedAndFallbackSummarizesEvidence() throws Exception {
        String raw = "{\"total_count\":1,\"items\":[{\"name\":\"Connect-Ai\",\"full_name\":\"okruti-sunita/Connect-Ai\","
                + "\"language\":\"Java\",\"default_branch\":\"main\"}]}";
        AgentExecutionState search = state("search_repositories", Map.of("query", "user:okruti-sunita"), raw);
        String rawAnswer = "{\"total_count\":1,\"items\":[{\"name\":\"Connect-Ai\",\"full_name\":\"okruti-sunita/Connect-Ai\","
                + "\"language\":\"Java\",\"default_branch\":\"main\"}]}";
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rawAnswer)
                        + ",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}")
                .then("{\"answer\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rawAnswer)
                        + ",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, search);

        assertThat(llm.prompts).hasSize(2);
        assertThat(llm.prompts.get(1)).contains("raw JSON");
        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.answer()).contains("total count: 1", "name: Connect-Ai", "full name: okruti-sunita/Connect-Ai",
                "language: Java", "default branch: main", "[E1]").doesNotContain("{\"total_count\"");
        assertThat(answer.evidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.content()).contains(raw));
    }

    @Test
    void modelFailureWithTextEvidencePreservesTheReadableText() {
        SequenceLlm llm = new SequenceLlm().thenFail("empty answer");

        GroundedAnswer answer = generate(llm, GET_ME);

        assertThat(answer.answer()).contains("octo-user").doesNotContain("{\"login\"");
        assertThat(answer.evidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.content()).contains("octo-user"));
    }

    @Test
    void emptyStructuredModelAnswerFallsBackToReadableEvidence() {
        String raw = "{\"name\":\"Connect-Ai\",\"language\":\"Java\"}";
        AgentExecutionState search = state("search_repositories", Map.of("query", "user:okruti-sunita"), raw);
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":\"\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}")
                .then("{\"answer\":\"\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");

        GroundedAnswer answer = generate(llm, search);

        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(answer.answer()).contains("name: Connect-Ai", "language: Java", "[E1]")
                .doesNotContain("{\"name\"");
        assertThat(answer.evidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.content()).contains(raw));
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
    void completeAnswerClaimIsDowngradedWhenOneToolFailedOrCatalogWasPartial() {
        AgentExecutionStep success = new AgentExecutionStep(1,
                new ToolSelectionPlan(SERVER_ID, "list_commits", Map.of("year", 2026), "list commits"),
                new ToolExecutionResult("list_commits", false,
                        List.of(new ToolExecutionContent("text", "commit abc")), null), AgentStepStatus.SUCCESS);
        AgentExecutionStep failure = new AgentExecutionStep(2,
                new ToolSelectionPlan(SERVER_ID, "list_tickets", Map.of("year", 2026), "list tickets"),
                new ToolExecutionResult("list_tickets", true, List.of(), null), AgentStepStatus.FAILED,
                "permission denied", 1);
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of commits and tickets in 2026?")
                .addStep(success).addStep(failure).finish(AgentExecutionStatus.COMPLETED, "planner finished");
        SequenceLlm llm = new SequenceLlm().then("{\"answer\":\"The evidence reports commit `abc`.\","
                + "\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");
        GroundedAnswerContextBuilder builder = new GroundedAnswerContextBuilder();
        LlmGroundedAnswerGenerator generator = new LlmGroundedAnswerGenerator(llm, builder,
                new GroundingValidator(), new TemplateGroundedAnswerGenerator(builder));

        GroundedAnswer answer = generator.generate(state,
                new com.connectai.agent.AnswerHints(List.of(), Map.of(),
                        List.of("MCP discovery missed one connected server; available tools may be incomplete.")));

        assertThat(answer.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(answer.answer()).contains("permission denied", "discovery missed one connected server");
        assertThat(answer.evidence()).hasSize(1).first().satisfies(evidence -> assertThat(evidence.content()).contains("commit abc"));
    }

    @Test
    void zeroCannotBePresentedAsACompleteTotalWithoutCompleteEvidence() {
        AgentExecutionState state = AgentExecutionState.initial("What is the total number of commits?")
                .addStep(new AgentExecutionStep(1,
                        new ToolSelectionPlan(SERVER_ID, "list_commits", Map.of(), "list commits"),
                        new ToolExecutionResult("list_commits", false, List.of(),
                                Map.of("items", List.of(Map.of("sha", "abc")))), AgentStepStatus.SUCCESS));
        SequenceLlm llm = new SequenceLlm()
                .then("{\"answer\":\"There were zero commits.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}")
                .then("{\"answer\":\"There were zero commits.\",\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");
        GroundedAnswerContextBuilder builder = new GroundedAnswerContextBuilder();
        GroundedAnswer answer = new LlmGroundedAnswerGenerator(llm, builder, new GroundingValidator(),
                new TemplateGroundedAnswerGenerator(builder)).generate(state);

        assertThat(answer.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(answer.fallbackUsed()).isTrue();
        assertThat(llm.prompts.get(1)).contains("claims zero results without evidence");
    }

    @Test
    void answerablePartIsReturnedWithMissingCapabilityClearlyMarked() {
        AgentExecutionState state = state("list_commits", Map.of("year", 2026), "commit `abc` for 2026");
        SequenceLlm llm = new SequenceLlm().then("{\"answer\":\"The evidence includes commit `abc` from 2026.\","
                + "\"coverage\":\"full\",\"missing\":\"\",\"citations\":[\"E1\"]}");
        GroundedAnswerContextBuilder builder = new GroundedAnswerContextBuilder();
        GroundedAnswer answer = new LlmGroundedAnswerGenerator(llm, builder, new GroundingValidator(),
                new TemplateGroundedAnswerGenerator(builder)).generate(state,
                new com.connectai.agent.AnswerHints(List.of("tickets"), Map.of()));

        assertThat(answer.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(answer.answer()).contains("commit `abc`", "no connected tool provides data about tickets");
        assertThat(llm.prompts.get(0)).contains("cannot fully answer those parts");
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
