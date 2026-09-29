package com.connectai.llm;

import com.connectai.agent.AgentState;
import com.connectai.agent.Finding;
import com.connectai.agent.RuleBasedPlanner;
import com.connectai.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPlannerTest {

    private static final Set<SourceType> ALL =
            EnumSet.of(SourceType.GITHUB, SourceType.JIRA, SourceType.SPLUNK, SourceType.SLACK);

    private AgentState state(List<Finding> evidence, Set<SourceType> queried) {
        return new AgentState("Why did payment fail?", evidence, queried, ALL);
    }

    private LlmPlanner plannerWith(ScriptedLlm llm) {
        return new LlmPlanner(llm, new RuleBasedPlanner());
    }

    @Test
    void followsTheModelsChoice() {
        ScriptedLlm llm = new ScriptedLlm().returnsTool("{\"action\":\"JIRA\",\"reason\":\"ticket key found\"}");

        assertThat(plannerWith(llm).nextTool(state(List.of(), EnumSet.of(SourceType.GITHUB))))
                .contains(SourceType.JIRA);
    }

    @Test
    void offersTheModelOnlySourcesThatAreStillAllowed() {
        ScriptedLlm llm = new ScriptedLlm().returnsTool("{\"action\":\"FINISH\",\"reason\":\"enough\"}");

        plannerWith(llm).nextTool(state(List.of(), EnumSet.of(SourceType.GITHUB)));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) llm.lastTool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) props.get("action");
        // GITHUB was already queried, so it must not be offered again. FINISH is always offered.
        assertThat(action.get("enum")).isEqualTo(List.of("JIRA", "SPLUNK", "SLACK", "FINISH"));
    }

    @Test
    void finishMeansStop() {
        ScriptedLlm llm = new ScriptedLlm().returnsTool("{\"action\":\"FINISH\",\"reason\":\"enough\"}");

        assertThat(plannerWith(llm).nextTool(state(List.of(), EnumSet.of(SourceType.GITHUB)))).isEmpty();
    }

    @Test
    void anInvalidChoiceFallsBackToTheRules() {
        // QUEUE is not a connected tool, so the model's answer is rejected and the rules decide (GitHub first).
        ScriptedLlm llm = new ScriptedLlm().returnsTool("{\"action\":\"QUEUE\",\"reason\":\"guess\"}");

        assertThat(plannerWith(llm).nextTool(state(List.of(), EnumSet.noneOf(SourceType.class))))
                .contains(SourceType.GITHUB);
    }

    @Test
    void aModelFailureFallsBackToTheRules() {
        ScriptedLlm llm = new ScriptedLlm().fails();

        assertThat(plannerWith(llm).nextTool(state(List.of(), EnumSet.noneOf(SourceType.class))))
                .contains(SourceType.GITHUB);
    }

    @Test
    void doesNotCallTheModelWhenNothingIsLeftToChoose() {
        ScriptedLlm llm = new ScriptedLlm();

        assertThat(plannerWith(llm).nextTool(state(List.of(), ALL))).isEmpty();
        assertThat(llm.calls).isZero();
    }

    @Test
    void earlierEvidenceIsShownToTheModel() {
        ScriptedLlm llm = new ScriptedLlm().returnsTool("{\"action\":\"JIRA\",\"reason\":\"r\"}");
        List<Finding> evidence = List.of(new Finding(SourceType.GITHUB, "PR #892", "Linked ticket: ORDER-4821", 0.9));

        plannerWith(llm).nextTool(state(evidence, EnumSet.of(SourceType.GITHUB)));

        assertThat(llm.lastUser).contains("ORDER-4821");
    }
}
