package com.connectai.agent;

import com.connectai.domain.SourceType;
import com.connectai.tools.fake.FakeTool;
import com.connectai.tools.fake.ScenarioCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests: no Spring, no database - just the loop and its collaborators. */
class AgentOrchestratorTest {

    private final ScenarioCatalog catalog = new ScenarioCatalog();

    private AgentOrchestrator agent(List<EvidenceTool> tools, Planner planner) {
        return new AgentOrchestrator(new ToolRegistry(tools), planner, new EvidenceRanker(), new TemplateAnswerGenerator(), request -> new ToolExecutionResult(request.toolName(), false, List.of(), null));
    }

    private AgentOrchestrator defaultAgent() {
        return agent(List.of(
                new FakeTool(SourceType.GITHUB, catalog),
                new FakeTool(SourceType.JIRA, catalog),
                new FakeTool(SourceType.SPLUNK, catalog),
                new FakeTool(SourceType.SLACK, catalog)), new RuleBasedPlanner());
    }

    private static Set<SourceType> sourcesOf(AgentResult result) {
        return result.evidence().stream().map(Finding::source).collect(Collectors.toSet());
    }

    @Test
    void paymentQuestion_usesAllFourToolsAndCitesEverySource() {
        AgentResult result = defaultAgent().investigate("Why did payment failures start after release 2.4.1?");

        assertThat(sourcesOf(result)).containsExactlyInAnyOrder(
                SourceType.GITHUB, SourceType.JIRA, SourceType.SPLUNK, SourceType.SLACK);
        assertThat(result.evidence().get(0).source()).isEqualTo(SourceType.SPLUNK); // 0.98, strongest
        assertThat(result.answer()).contains("[GITHUB: PR #456 / commit abc123]", "[JIRA: JIRA-123]");
    }

    @Test
    void checkoutQuestion_skipsLogsBecauseNothingLooksBroken() {
        AgentResult result = defaultAgent().investigate("What changed in the checkout service last week?");

        assertThat(sourcesOf(result)).containsExactlyInAnyOrder(SourceType.GITHUB, SourceType.JIRA);
    }

    @Test
    void unknownQuestion_admitsItHasNoEvidenceInsteadOfGuessing() {
        AgentResult result = defaultAgent().investigate("What is the weather today?");

        assertThat(result.evidence()).isEmpty();
        assertThat(result.answer()).contains("won't guess");
    }

    @Test
    void brokenGithub_doesNotSinkTheInvestigation() {
        EvidenceTool brokenGithub = new EvidenceTool() {
            @Override
            public SourceType source() {
                return SourceType.GITHUB;
            }

            @Override
            public List<Finding> gather(String question, List<Finding> evidenceSoFar) {
                throw new IllegalStateException("GitHub is down");
            }
        };
        AgentOrchestrator agent = agent(List.of(
                brokenGithub,
                new FakeTool(SourceType.JIRA, catalog),
                new FakeTool(SourceType.SPLUNK, catalog),
                new FakeTool(SourceType.SLACK, catalog)), new RuleBasedPlanner());

        AgentResult result = agent.investigate("Why did payment failures start after release 2.4.1?");

        // Jira was never asked: the ticket key lived in the GitHub evidence, and GitHub failed.
        assertThat(sourcesOf(result)).containsExactlyInAnyOrder(SourceType.SPLUNK, SourceType.SLACK);
    }

    @Test
    void aPlannerThatNeverStopsIsCutOffAtMaxSteps() {
        AtomicInteger calls = new AtomicInteger();
        EvidenceTool counting = new EvidenceTool() {
            @Override
            public SourceType source() {
                return SourceType.GITHUB;
            }

            @Override
            public List<Finding> gather(String question, List<Finding> evidenceSoFar) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        Planner stubborn = state -> Optional.of(SourceType.GITHUB);

        agent(List.of(counting), stubborn).investigate("anything");

        assertThat(calls.get()).isEqualTo(AgentOrchestrator.MAX_STEPS);
    }
}
