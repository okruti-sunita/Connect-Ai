package com.connectai.service;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.AnswerHints;
import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerGenerator;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ToolShortlister;
import com.connectai.agent.analysis.IssueAnalysisEngine;
import com.connectai.agent.fastpath.FastPathEngine;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.repository.McpServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one dynamic investigation to a terminal state: catalog -> bounded agent loop
 * (each step persisted as it finishes) -> grounded answer -> terminal status.
 *
 * {@link #run} never throws: any unexpected error is recorded as FAILED, so an
 * investigation can never stay RUNNING because of an exception. Logs carry the
 * investigation id (MDC key {@code investigationId}) as the correlation id.
 */
@Component
public class InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRunner.class);

    private final AgentToolCatalog toolCatalog;
    private final ControlledAgentExecutor executor;
    private final GroundedAnswerGenerator answerGenerator;
    private final InvestigationStore store;
    private final McpServerRepository servers;
    private final FastPathEngine fastPath;
    private final LearnedRecipeService learned;
    private final IssueAnalysisEngine analysis;

    public InvestigationRunner(
            AgentToolCatalog toolCatalog,
            ControlledAgentExecutor executor,
            GroundedAnswerGenerator answerGenerator,
            InvestigationStore store,
            McpServerRepository servers,
            ObjectProvider<FastPathEngine> fastPath,
            ObjectProvider<LearnedRecipeService> learned,
            ObjectProvider<IssueAnalysisEngine> analysis) {
        this.toolCatalog = toolCatalog;
        this.executor = executor;
        this.answerGenerator = answerGenerator;
        this.store = store;
        this.servers = servers;
        this.fastPath = fastPath.getIfAvailable();
        this.learned = learned.getIfAvailable();
        this.analysis = analysis.getIfAvailable();
    }

    public void run(UUID investigationId, String question) {
        MDC.put("investigationId", investigationId.toString());
        long started = System.nanoTime();
        try {
            List<McpToolCandidate> tools = toolCatalog.getAvailableTools();
            log.info("Investigation run started: id={}, availableTools={}", investigationId, tools.size());
            Map<UUID, String> connections = serverNames(tools);

            // Hybrid routing: recognised questions are answered by a deterministic recipe with no model
            // call; everything else goes to the AI agent.
            String routeDetail = "Fast path disabled; the AI agent plans every question.";
            String analysisNote = "";
            // Issue analysis: "why is PAY-12 happening / estimate PAY-12" -> tracker + code evidence, scored.
            if (analysis != null && IssueAnalysisEngine.issueKey(question).isPresent()) {
                var decision = analysis.analyze(question, tools, step -> store.appendStep(investigationId, step));
                if (decision.result().isPresent()) {
                    var r = decision.result().get();
                    store.complete(investigationId, r.execution(), r.answer(), connections, "ANALYSIS", r.explanation());
                    log.info("Investigation answered by issue analysis: id={}, elapsedMs={}",
                            investigationId, (System.nanoTime() - started) / 1_000_000);
                    return;
                }
                analysisNote = decision.explanation() + " ";
            }
            if (fastPath != null) {
                var decision = fastPath.decide(question, tools, step -> store.appendStep(investigationId, step),
                        execution -> answerGenerator.generate(execution, new AnswerHints(List.of(), connections)));
                routeDetail = analysisNote + decision.explanation();
                var fast = decision.result();
                if (fast.isPresent()) {
                    store.complete(investigationId, fast.get().execution(), fast.get().answer(), connections,
                            "FAST_PATH", routeDetail);
                    log.info("Investigation answered by the fast path: id={}, intent={}, templateAnswer={}, elapsedMs={}",
                            investigationId, fast.get().intent(), fast.get().templateAnswer(),
                            (System.nanoTime() - started) / 1_000_000);
                    return;
                }
            }

            // Self-learning: a question shaped like one the agent already answered replays that plan.
            if (learned != null) {
                var replay = learned.replay(question, tools, step -> store.appendStep(investigationId, step),
                        execution -> answerGenerator.generate(execution, new AnswerHints(List.of(), connections)));
                if (replay.isPresent()) {
                    var r = replay.get();
                    store.complete(investigationId, r.execution(), r.answer(), connections, "LEARNED", r.explanation());
                    learned.review(r.recipeId(), InvestigationStore.statusFor(r.execution(), r.answer()));
                    log.info("Investigation answered by a learned recipe: id={}, recipe={}, elapsedMs={}",
                            investigationId, r.recipeId(), (System.nanoTime() - started) / 1_000_000);
                    return;
                }
            }

            AgentExecutionState execution = executor.execute(
                    question,
                    tools,
                    step -> store.appendStep(investigationId, step));

            List<String> uncovered = ToolShortlister.topicCoverage(question, tools).uncoveredCapabilities();
            if (!uncovered.isEmpty()) {
                log.info("Question asks for data no connected tool provides: {}", uncovered);
            }
            GroundedAnswer answer = answerGenerator.generate(execution, new AnswerHints(uncovered, connections));
            store.complete(investigationId, execution, answer, connections, "AI_AGENT", routeDetail);
            if (learned != null) {
                learned.learn(investigationId, question, execution, InvestigationStore.statusFor(execution, answer), tools);
            }

            log.info("Investigation run finished: id={}, executionStatus={}, grounded={}, fallbackUsed={}, elapsedMs={}",
                    investigationId, execution.status(), answer.grounded(), answer.fallbackUsed(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (Throwable throwable) {
            log.error("Investigation run crashed: id={}, cause={}", investigationId, throwable.toString(), throwable);
            store.fail(investigationId, "Investigation failed: " + safeMessage(throwable));
            if (throwable instanceof Error error && !(throwable instanceof StackOverflowError)) {
                throw error;
            }
        } finally {
            MDC.remove("investigationId");
        }
    }

    private Map<UUID, String> serverNames(List<McpToolCandidate> tools) {
        Map<UUID, String> names = new HashMap<>();
        tools.stream().map(McpToolCandidate::serverId).distinct().forEach(id ->
                servers.findById(id).map(EMcpServer::getName).ifPresent(name -> names.put(id, name)));
        return names;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
