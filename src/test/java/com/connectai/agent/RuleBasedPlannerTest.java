package com.connectai.agent;

import com.connectai.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedPlannerTest {

    private final RuleBasedPlanner planner = new RuleBasedPlanner();
    private static final Set<SourceType> ALL =
            EnumSet.of(SourceType.GITHUB, SourceType.JIRA, SourceType.SPLUNK, SourceType.SLACK);

    private AgentState state(String question, List<Finding> evidence, Set<SourceType> queried) {
        return new AgentState(question, evidence, queried, ALL);
    }

    @Test
    void alwaysStartsWithGithub() {
        assertThat(planner.nextTool(state("Why did payment fail?", List.of(), EnumSet.noneOf(SourceType.class))))
                .contains(SourceType.GITHUB);
    }

    @Test
    void asksJiraOnlyAfterATicketKeyAppearsInTheEvidence() {
        Set<SourceType> queried = EnumSet.of(SourceType.GITHUB);

        // No ticket key anywhere -> skips Jira, goes to logs because "fail" sounds like a runtime problem.
        assertThat(planner.nextTool(state("Why did payment fail?", List.of(), queried)))
                .contains(SourceType.SPLUNK);

        // GitHub evidence mentions JIRA-9 -> Jira is next.
        List<Finding> withTicket = List.of(
                new Finding(SourceType.GITHUB, "PR #1", "Linked ticket: JIRA-9", 0.9));
        assertThat(planner.nextTool(state("Why did payment fail?", withTicket, queried)))
                .contains(SourceType.JIRA);
    }

    @Test
    void skipsLogsWhenNothingSoundsLikeARuntimeProblem() {
        assertThat(planner.nextTool(state("What changed in checkout last week?", List.of(), EnumSet.of(SourceType.GITHUB))))
                .contains(SourceType.SLACK);
    }

    @Test
    void neverProposesDisconnectedOrRepeatedTools() {
        AgentState onlyGithub = new AgentState("anything", List.of(), EnumSet.noneOf(SourceType.class),
                EnumSet.of(SourceType.GITHUB));
        assertThat(planner.nextTool(onlyGithub)).contains(SourceType.GITHUB);

        AgentState githubDone = new AgentState("anything", List.of(), EnumSet.of(SourceType.GITHUB),
                EnumSet.of(SourceType.GITHUB));
        assertThat(planner.nextTool(githubDone)).isEmpty();
    }

    @Test
    void stopsWhenEveryToolHasBeenAsked() {
        assertThat(planner.nextTool(state("Why did payment fail?", List.of(), ALL))).isEmpty();
    }
}
