package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityFactsTest {

    private static final UUID SERVER = UUID.randomUUID();

    private static AgentExecutionState afterGetMe() {
        return AgentExecutionState.initial("q").addStep(new AgentExecutionStep(1,
                new ToolSelectionPlan(SERVER, "get_me", Map.of(), "r"),
                new ToolExecutionResult("get_me", false,
                        List.of(new ToolExecutionContent("text", "{\"login\":\"octo-user\",\"id\":1}")), null),
                AgentStepStatus.SUCCESS));
    }

    private static ToolSelectionPlan search(String query) {
        return new ToolSelectionPlan(SERVER, "search_repositories", Map.of("query", query), "r");
    }

    @Test
    void loginComesFromAnArgumentFreeIdentityCall() {
        assertThat(IdentityFacts.authenticatedLogin(afterGetMe())).contains("octo-user");
        assertThat(IdentityFacts.authenticatedLogin(AgentExecutionState.initial("q"))).isEmpty();
    }

    /** The reported failure: "Which repositories do I own?" searched everyone's repos for "my repos". */
    @Test
    void unscopedSearchForAFirstPersonQuestionIsRejected() {
        assertThat(IdentityFacts.scopingProblem("Which repositories do I own?", search("my repos"), afterGetMe()))
                .get().asString().contains("\"user:octo-user\"");
        assertThat(IdentityFacts.scopingProblem("Which repositories do I own?", search("user:octo-user"), afterGetMe()))
                .isEmpty();
    }

    /** Reported case: the bare login was used as a keyword instead of a user: qualifier. */
    @Test
    void bareLoginIsNotAScope() {
        assertThat(IdentityFacts.scopingProblem("Which repositories do I own?", search("octo-user"), afterGetMe())).isPresent();
        assertThat(IdentityFacts.scopingProblem("Which PRs are in my repos?", search("is:pr owner:octo-user"), afterGetMe())).isEmpty();
        assertThat(IdentityFacts.scopingProblem("Which PRs are in my repos?", search("repo:octo-user/api is:open"), afterGetMe())).isEmpty();
        assertThat(IdentityFacts.scopingProblem("Which repositories do I own?", search("user:octo-user-2"), afterGetMe())).isPresent();
    }

    @Test
    void eachConnectionIsScopedToItsOwnAccount() {
        UUID work = UUID.randomUUID();
        AgentExecutionState state = afterGetMe().addStep(new AgentExecutionStep(2,
                new ToolSelectionPlan(work, "get_me", Map.of(), "r"),
                new ToolExecutionResult("get_me", false,
                        List.of(new ToolExecutionContent("text", "{\"login\":\"work-account\"}")), null),
                AgentStepStatus.SUCCESS));

        assertThat(IdentityFacts.logins(state)).containsEntry(SERVER, "octo-user").containsEntry(work, "work-account");
        ToolSelectionPlan wrongAccount = new ToolSelectionPlan(work, "search_repositories", Map.of("query", "user:octo-user"), "r");
        assertThat(IdentityFacts.scopingProblem("Which repositories do I own?", wrongAccount, state))
                .get().asString().contains("user:work-account");
    }

    @Test
    void otherQuestionsAndToolsAreNotAffected() {
        assertThat(IdentityFacts.scopingProblem("Find repos about kafka", search("kafka"), afterGetMe())).isEmpty();
        ToolSelectionPlan listCommits = new ToolSelectionPlan(SERVER, "list_commits", Map.of("owner", "x", "repo", "y"), "r");
        assertThat(IdentityFacts.scopingProblem("show my commits in x/y", listCommits, afterGetMe())).isEmpty();
        assertThat(IdentityFacts.scopingProblem("Which repositories do I own?", search("my repos"),
                AgentExecutionState.initial("q"))).isEmpty();
    }
}
