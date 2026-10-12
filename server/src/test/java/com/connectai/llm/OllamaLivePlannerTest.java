package com.connectai.llm;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.PlannerDecision;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.agent.ToolShortlisterTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Talks to a REAL local Ollama model. Skipped (not passed) unless enabled with
 * {@code -Dconnectai.live-ollama=true} and the model is installed. Model via
 * {@code -Dconnectai.live-ollama.model} (default qwen3:1.7b).
 *
 * Each scenario is repeated several times: one lucky reply proves nothing.
 */
class OllamaLivePlannerTest {

    private static final String MODEL = System.getProperty("connectai.live-ollama.model", "qwen3:1.7b");
    private static final String BASE_URL = System.getProperty("connectai.live-ollama.url", "http://localhost:11434");
    private static final int RUNS = Integer.getInteger("connectai.live-ollama.runs", 5);
    private static final String QUESTION =
            "Use the available GitHub MCP tools to identify my authenticated GitHub account and report the login. "
                    + "Use real tool evidence and do not invent results.";

    private static OllamaClient client;

    @BeforeAll
    static void requireModel() {
        assumeTrue(Boolean.getBoolean("connectai.live-ollama"), "live Ollama tests not enabled");
        client = new OllamaClient(BASE_URL, MODEL, Duration.ofSeconds(180), new ObjectMapper(), 8192, 512, 0.0);
        List<String> installed;
        try {
            installed = client.installedModels();
        } catch (LlmException e) {
            assumeTrue(false, "Ollama not reachable: " + e.getMessage());
            return;
        }
        assumeTrue(OllamaClient.isInstalled(MODEL, installed), "model not installed: " + MODEL);
    }

    @Test
    void selectsGetMeFromTheRealSizedCatalogEveryRun() {
        List<McpToolCandidate> catalog = ToolShortlisterTest.githubLikeCatalog();
        LlmMcpToolPlanner planner = new LlmMcpToolPlanner(client, 8, 3);

        int getMe = 0;
        for (int i = 0; i < RUNS; i++) {
            long started = System.nanoTime();
            PlannerDecision decision = planner.decide(QUESTION, catalog, AgentExecutionState.initial(QUESTION));
            long ms = (System.nanoTime() - started) / 1_000_000;
            String chosen = decision.isFinish() ? "FINISH" : decision.plan().toolName();
            System.out.printf("[live-ollama] run %d: chose %s in %d ms%n", i + 1, chosen, ms);
            if ("get_me".equals(chosen)) getMe++;
        }
        assertThat(getMe).as("runs choosing get_me out of " + RUNS).isEqualTo(RUNS);
    }

    @Test
    void finishesOnceGetMeEvidenceExists() {
        McpToolCandidate getMe = ToolShortlisterTest.githubLikeCatalog().get(0);
        AgentExecutionState state = AgentExecutionState.initial(QUESTION).addStep(new AgentExecutionStep(1,
                new ToolSelectionPlan(getMe.serverId(), "get_me", Map.of(), "identify user"),
                new ToolExecutionResult("get_me", false,
                        List.of(new ToolExecutionContent("text", "{\"login\":\"live-test-user\",\"id\":1}")), null),
                AgentStepStatus.SUCCESS));

        int finished = 0;
        for (int i = 0; i < RUNS; i++) {
            PlannerDecision decision = new LlmMcpToolPlanner(client, 8, 3)
                    .decide(QUESTION, ToolShortlisterTest.githubLikeCatalog(), state);
            System.out.printf("[live-ollama] after evidence, run %d: %s%n", i + 1,
                    decision.isFinish() ? decision.finishReason() : decision.plan().toolName());
            if (decision.isFinish()) finished++;
        }
        assertThat(finished).as("runs finishing after evidence out of " + RUNS).isEqualTo(RUNS);
    }

    /** Production answer path (LlmGroundedAnswerGenerator + GroundingValidator) with the real model. */
    @Test
    void productionAnswerGeneratorReturnsAGroundedAnswerWithTheRealLogin() {
        McpToolCandidate getMe = ToolShortlisterTest.githubLikeCatalog().get(0);
        AgentExecutionState state = AgentExecutionState.initial(QUESTION).addStep(new AgentExecutionStep(1,
                new ToolSelectionPlan(getMe.serverId(), "get_me", Map.of(), "identify user"),
                new ToolExecutionResult("get_me", false,
                        List.of(new ToolExecutionContent("text", "{\"login\":\"live-test-user\",\"id\":1}")), null),
                AgentStepStatus.SUCCESS)).finish(com.connectai.agent.AgentExecutionStatus.COMPLETED, "done");
        var contextBuilder = new com.connectai.agent.GroundedAnswerContextBuilder();
        var generator = new LlmGroundedAnswerGenerator(client, contextBuilder, new GroundingValidator(),
                new com.connectai.agent.TemplateGroundedAnswerGenerator(contextBuilder));

        int modelAnswers = 0;
        for (int i = 0; i < RUNS; i++) {
            var answer = generator.generate(state);
            System.out.printf("[live-ollama] answer run %d (fallback=%s): %s%n", i + 1, answer.fallbackUsed(),
                    answer.answer().replaceAll("\s+", " "));
            assertThat(answer.grounded()).isTrue();
            assertThat(answer.answer()).contains("live-test-user").contains("[E1]");
            if (!answer.fallbackUsed()) modelAnswers++;
        }
        assertThat(modelAnswers).as("runs answered by the model rather than the template").isEqualTo(RUNS);
    }
}
