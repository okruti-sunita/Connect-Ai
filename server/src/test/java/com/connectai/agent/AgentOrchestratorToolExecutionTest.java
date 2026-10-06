package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorToolExecutionTest {

    @Test
    void executeToolDelegatesToTheApplicationToolExecutionPort() {
        UUID serverId = UUID.randomUUID();
        ToolExecutionRequest request = new ToolExecutionRequest(
                serverId,
                "search_commits",
                Map.of("query", "payment failure"));

        AtomicReference<ToolExecutionRequest> received =
                new AtomicReference<>();

        ToolExecutionPort port = actual -> {
            received.set(actual);
            return new ToolExecutionResult(
                    actual.toolName(),
                    false,
                    List.of(new ToolExecutionContent("text", "commit abc123")),
                    null);
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new ToolRegistry(List.of()),
                state -> java.util.Optional.empty(),
                new EvidenceRanker(),
                (question, evidence) -> "no-op",
                port);

        ToolExecutionResult result = orchestrator.executeTool(request);

        assertThat(result.toolName()).isEqualTo("search_commits");
        assertThat(result.error()).isFalse();
        assertThat(result.content().get(0).text()).isEqualTo("commit abc123");
        assertThat(received.get()).isEqualTo(request);
    }

    @Test
    void toolExecutionPortIsNotUsedByTheExistingInvestigationLoopYet() {
        ToolExecutionPort failingPort = request -> {
            throw new AssertionError(
                    "Task 5 must not silently change the existing planner loop");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new ToolRegistry(List.of()),
                state -> java.util.Optional.empty(),
                new EvidenceRanker(),
                (question, evidence) -> "done",
                failingPort);

        AgentResult result = orchestrator.investigate("existing investigation");

        assertThat(result.answer()).isEqualTo("done");
        assertThat(result.evidence()).isEmpty();
    }
}
