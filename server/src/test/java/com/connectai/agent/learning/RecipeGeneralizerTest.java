package com.connectai.agent.learning;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.agent.learning.RecipeGeneralizer.Identity;
import com.connectai.agent.learning.RecipeGeneralizer.RecipeStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeGeneralizerTest {

    private static final UUID SERVER = UUID.randomUUID();

    private static McpToolCandidate tool(String name, String description) {
        return new McpToolCandidate(SERVER, name, name, description, Map.of("type", "object"), null, "GitHub");
    }

    private static final List<McpToolCandidate> TOOLS = List.of(
            tool("get_me", "Get details of the authenticated GitHub user"),
            tool("search_issues", "Search issues"),
            tool("search_repositories", "Search repositories"),
            tool("list_workflow_runs", "List workflow runs"),
            tool("get_issue", "Get an issue"),
            tool("create_issue", "Create an issue"));

    private static AgentExecutionStep step(int n, String toolName, Map<String, Object> args, String output) {
        return new AgentExecutionStep(n, new ToolSelectionPlan(SERVER, toolName, args, "test"),
                new ToolExecutionResult(toolName, false, List.of(new ToolExecutionContent("text", output)), null),
                AgentStepStatus.SUCCESS);
    }

    private static AgentExecutionState run(String question, AgentExecutionStep... steps) {
        AgentExecutionState state = AgentExecutionState.initial(question);
        for (AgentExecutionStep step : steps) state = state.addStep(step);
        return state;
    }

    @Test
    void questionsOfTheSameShapeShareATemplate() {
        QuestionTemplate a = QuestionTemplate.of("Summarize the open issues of okruti/awsdemo1?");
        QuestionTemplate b = QuestionTemplate.of("summarize the open issues of acme/api");

        assertThat(a.template()).isEqualTo(b.template()).isEqualTo("summarize the open issues of {owner}/{repo}");
        assertThat(a.slots()).containsEntry("owner", "okruti").containsEntry("repo", "awsdemo1");
        assertThat(QuestionTemplate.of("why is PAY-12 blocked").template()).isEqualTo("why is {key} blocked");
        assertThat(QuestionTemplate.of("explain issue #42 in acme/api").slots())
                .containsEntry("number", "42").containsEntry("repo", "api");
    }

    @Test
    void questionValuesAndTheLoginBecomeSlots() {
        String question = "summarize my open issues in okruti/awsdemo1";
        QuestionTemplate template = QuestionTemplate.of(question);
        AgentExecutionState execution = run(question,
                step(1, "get_me", Map.of(), "{\"login\":\"octo\",\"id\":9001}"),
                step(2, "search_issues", Map.of("query", "repo:okruti/awsdemo1 is:open author:octo", "perPage", 20),
                        "{\"total_count\":1,\"items\":[{\"title\":\"Bug\"}]}"));

        RecipeGeneralizer.Outcome outcome = RecipeGeneralizer.generalize(template, question, execution, TOOLS);

        assertThat(outcome.steps()).hasValueSatisfying(steps -> {
            assertThat(steps).extracting(RecipeStep::toolName).containsExactly("get_me", "search_issues");
            assertThat(steps.get(1).arguments())
                    .containsEntry("query", "repo:{{owner}}/{{repo}} is:open author:{{login}}")
                    .containsEntry("perPage", 20);
        });

        QuestionTemplate next = QuestionTemplate.of("summarize my open issues in acme/api");
        assertThat(RecipeGeneralizer.instantiate(outcome.steps().get().get(1), next.slots(), new Identity("hubot", "7")))
                .hasValue(Map.of("query", "repo:acme/api is:open author:hubot", "perPage", 20));
        assertThat(RecipeGeneralizer.instantiate(outcome.steps().get().get(1), next.slots(), null))
                .as("a step that needs the login cannot run without it").isEmpty();
    }

    @Test
    void numericSlotsStayNumeric() {
        String question = "explain issue #42 in acme/api";
        AgentExecutionState execution = run(question,
                step(1, "get_issue", Map.of("owner", "acme", "repo", "api", "issue_number", 42), "{\"title\":\"Crash\"}"));

        var steps = RecipeGeneralizer.generalize(QuestionTemplate.of(question), question, execution, TOOLS).steps().orElseThrow();
        assertThat(steps.get(0).arguments()).containsEntry("issue_number", "{{#number}}");
        assertThat(RecipeGeneralizer.instantiate(steps.get(0), QuestionTemplate.of("explain issue #7 in acme/web").slots(), null))
                .hasValue(Map.of("owner", "acme", "repo", "web", "issue_number", 7L));
    }

    @Test
    void plansThatDependOnEarlierResultsAreNotLearned() {
        String question = "which of my repositories has the most failed workflow runs";
        AgentExecutionState execution = run(question,
                step(1, "get_me", Map.of(), "{\"login\":\"octo\"}"),
                step(2, "search_repositories", Map.of("query", "user:octo"), "{\"items\":[{\"name\": \"awsdemo1\"}]}"),
                step(3, "list_workflow_runs", Map.of("owner", "octo", "repo", "awsdemo1"), "{\"workflow_runs\":[]}"));

        RecipeGeneralizer.Outcome outcome = RecipeGeneralizer.generalize(QuestionTemplate.of(question), question, execution, TOOLS);

        assertThat(outcome.steps()).isEmpty();
        assertThat(outcome.reason()).contains("awsdemo1").contains("earlier tool result");
    }

    @Test
    void writeToolsAreNeverLearned() {
        String question = "summarize issues in acme/api";
        AgentExecutionState execution = run(question,
                step(1, "create_issue", Map.of("owner", "acme", "repo", "api", "title", "x"), "{}"));

        assertThat(RecipeGeneralizer.generalize(QuestionTemplate.of(question), question, execution, TOOLS).reason())
                .contains("not read-only");
    }
}
