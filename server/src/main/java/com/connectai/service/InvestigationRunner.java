package com.connectai.service;

import com.connectai.domain.InvestigationStatus;
import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.AnswerHints;
import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerGenerator;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ProviderConstraint;
import com.connectai.agent.SafeDiagnostic;
import com.connectai.agent.ToolShortlister;
import com.connectai.agent.analysis.IssueAnalysisEngine;
import com.connectai.agent.fastpath.FastPathEngine;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.repository.McpServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        InvestigationTrace.bind(investigationId);
        long started = System.nanoTime();
        try {
            InvestigationTrace.emit(investigationId, "INVESTIGATION_RUN_STARTED", "STARTED", 0,
                    Map.of("questionLength", question == null ? 0 : question.length()));
            long stageStarted = System.nanoTime();
            var catalog = toolCatalog.snapshot();
            List<McpToolCandidate> tools = catalog.tools();
            log.info("Investigation run started: id={}, availableTools={}", investigationId, tools.size());
            InvestigationTrace.emit(investigationId, "CAPABILITY_DISCOVERY", "SUCCEEDED",
                    elapsedMs(stageStarted), Map.of(
                            "toolCount", tools.size(),
                            "warningCount", catalog.warnings().size(),
                            "connectionIds", tools.stream().map(McpToolCandidate::serverId).distinct().map(UUID::toString).toList(),
                            "providerKeys", tools.stream().map(McpToolCandidate::providerKey).filter(key -> !key.isBlank()).distinct().toList()));
            Map<UUID, String> connections = serverNames(tools);

            // Hybrid routing: recognised questions are answered by a deterministic recipe with no model
            // call; everything else goes to the AI agent.
            String routeDetail = "Fast path disabled; the AI agent plans every question.";
            String analysisNote = "";
            // Issue analysis: "why is PAY-12 happening / estimate PAY-12" -> tracker + code evidence, scored.
            if (analysis != null && IssueAnalysisEngine.issueKey(question).isPresent()) {
                stageStarted = System.nanoTime();
                var decision = analysis.analyze(question, tools, step -> store.appendStep(investigationId, step));
                if (decision.result().isPresent()) {
                    var r = decision.result().get();
                    store.complete(investigationId, r.execution(), r.answer(), connections, "ANALYSIS", r.explanation());
                    InvestigationTrace.emit(investigationId, "INVESTIGATION_COMPLETED", "SUCCEEDED",
                            elapsedMs(started), Map.of("route", "ANALYSIS", "stepCount", r.execution().stepCount(),
                                    "evidenceCount", r.answer().evidence().size(), "answerAvailable", !r.answer().answer().isBlank()));
                    log.info("Investigation answered by issue analysis: id={}, elapsedMs={}",
                            investigationId, (System.nanoTime() - started) / 1_000_000);
                    return;
                }
                InvestigationTrace.emit(investigationId, "INTENT_RESOLUTION", "FALLBACK",
                        elapsedMs(stageStarted), Map.of("route", "ANALYSIS", "reason", "issue analysis had no result"));
                analysisNote = decision.explanation() + " ";
            }
            if (fastPath != null) {
                stageStarted = System.nanoTime();
                var decision = fastPath.decide(question, tools, step -> store.appendStep(investigationId, step),
                        execution -> answerGenerator.generate(execution, new AnswerHints(List.of(), connections)));
                routeDetail = analysisNote + decision.explanation();
                var fast = decision.result();
                if (fast.isPresent()) {
                    store.complete(investigationId, fast.get().execution(), fast.get().answer(), connections,
                            "FAST_PATH", routeDetail);
                    InvestigationTrace.emit(investigationId, "INTENT_RESOLUTION", "FAST_PATH",
                            elapsedMs(stageStarted), Map.of("route", "FAST_PATH", "stepCount", fast.get().execution().stepCount(),
                                    "evidenceCount", fast.get().answer().evidence().size()));
                    InvestigationTrace.emit(investigationId, "INVESTIGATION_COMPLETED", "SUCCEEDED",
                            elapsedMs(started), Map.of("route", "FAST_PATH", "stepCount", fast.get().execution().stepCount(),
                                    "evidenceCount", fast.get().answer().evidence().size(), "answerAvailable", !fast.get().answer().answer().isBlank()));
                    log.info("Investigation answered by the fast path: id={}, intent={}, templateAnswer={}, elapsedMs={}",
                            investigationId, fast.get().intent(), fast.get().templateAnswer(),
                            (System.nanoTime() - started) / 1_000_000);
                    return;
                }
                InvestigationTrace.emit(investigationId, "INTENT_RESOLUTION", "HANDOFF",
                        elapsedMs(stageStarted), Map.of("route", "AI_AGENT", "reason", "fast path did not produce a result"));
            }

            // Self-learning: a question shaped like one the agent already answered replays that plan.
            if (learned != null) {
                stageStarted = System.nanoTime();
                var replay = learned.replay(question, tools, step -> store.appendStep(investigationId, step),
                        execution -> answerGenerator.generate(execution, new AnswerHints(List.of(), connections)));
                if (replay.isPresent()) {
                    var r = replay.get();
                    store.complete(investigationId, r.execution(), r.answer(), connections, "LEARNED", r.explanation());
                    InvestigationTrace.emit(investigationId, "INTENT_RESOLUTION", "LEARNED_RECIPE",
                            elapsedMs(stageStarted), Map.of("route", "LEARNED", "stepCount", r.execution().stepCount(),
                                    "evidenceCount", r.answer().evidence().size()));
                    InvestigationTrace.emit(investigationId, "INVESTIGATION_COMPLETED", "SUCCEEDED",
                            elapsedMs(started), Map.of("route", "LEARNED", "stepCount", r.execution().stepCount(),
                                    "evidenceCount", r.answer().evidence().size(), "answerAvailable", !r.answer().answer().isBlank()));
                    learned.review(r.recipeId(), InvestigationStore.statusFor(r.execution(), r.answer()));
                    log.info("Investigation answered by a learned recipe: id={}, recipe={}, elapsedMs={}",
                            investigationId, r.recipeId(), (System.nanoTime() - started) / 1_000_000);
                    return;
                }
                InvestigationTrace.emit(investigationId, "INTENT_RESOLUTION", "HANDOFF",
                        elapsedMs(stageStarted), Map.of("route", "AI_AGENT", "reason", "no learned recipe matched"));
            }

            stageStarted = System.nanoTime();
            AgentExecutionState execution = executor.execute(
                    question,
                    tools,
                    step -> store.appendStep(investigationId, step));
            InvestigationTrace.emit(investigationId, "TOOL_EXECUTION", execution.hasSuccessfulStep() ? "PARTIAL_OR_SUCCEEDED" : "NO_SUCCESS",
                    elapsedMs(stageStarted), Map.of("stepCount", execution.stepCount(),
                            "successfulSteps", execution.steps().stream().filter(step -> step.status() == com.connectai.agent.AgentStepStatus.SUCCESS).count(),
                            "failedSteps", execution.steps().stream().filter(step -> step.status() == com.connectai.agent.AgentStepStatus.FAILED).count()));

            ProviderConstraint.Scope providerScope = ProviderConstraint.apply(question, tools);
            List<String> uncovered = ToolShortlister.topicCoverage(
                    question, tools, providerScope.constrained()).uncoveredCapabilities();
            if (!uncovered.isEmpty()) {
                log.info("Question asks for data no connected tool provides: {}", uncovered);
            }
            stageStarted = System.nanoTime();
            GroundedAnswer answer = answerGenerator.generate(
                    execution, new AnswerHints(uncovered, connections, catalog.warnings()));
            InvestigationTrace.emit(investigationId, "ANSWER_SYNTHESIS", answer.grounded() ? "GROUNDED" : "UNVERIFIED",
                    elapsedMs(stageStarted), Map.of("evidenceCount", answer.evidence().size(),
                            "coverage", answer.coverage().name(), "answerAvailable", !answer.answer().isBlank()));
            InvestigationStatus finalStatus = InvestigationStore.statusFor(execution, answer);
            store.complete(investigationId, execution, answer, connections, "AI_AGENT", routeDetail);
            InvestigationTrace.emit(investigationId, "INVESTIGATION_COMPLETED", finalStatus.name(),
                    elapsedMs(started), Map.of("route", "AI_AGENT", "stepCount", execution.stepCount(),
                            "evidenceCount", answer.evidence().size(), "answerAvailable", !answer.answer().isBlank(),
                            "grounded", answer.grounded(), "investigationStatus", finalStatus.name(),
                            "executionStatus", execution.status().name(),
                            "failureCategory", execution.failureCategory().name()));

            log.info("Investigation run finished: id={}, executionStatus={}, grounded={}, fallbackUsed={}, elapsedMs={}",
                    investigationId, execution.status(), answer.grounded(), answer.fallbackUsed(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (Throwable throwable) {
            String safeError = SafeDiagnostic.message(throwable, 500);
            InvestigationTrace.emit(investigationId, "INVESTIGATION_COMPLETED", "FAILED",
                    elapsedMs(started), Map.of("errorType", throwable.getClass().getSimpleName(), "terminalReason", safeError));
            log.error("Investigation run crashed: id={}, errorType={}, cause={}",
                    investigationId, throwable.getClass().getSimpleName(), safeError);
            store.fail(investigationId, "Investigation failed: " + safeError);
            if (throwable instanceof Error error && !(throwable instanceof StackOverflowError)) {
                throw error;
            }
        } finally {
            InvestigationTrace.clear();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private Map<UUID, String> serverNames(List<McpToolCandidate> tools) {
        Map<UUID, String> names = new HashMap<>();
        tools.stream().map(McpToolCandidate::serverId).distinct().forEach(id ->
                servers.findById(id).map(EMcpServer::getName).ifPresent(name -> names.put(id, name)));
        return names;
    }

}
