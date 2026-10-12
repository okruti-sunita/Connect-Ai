package com.connectai.service;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedEvidence;
import com.connectai.api.dto.InvestigationResponse;
import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.domain.InvestigationStep;
import com.connectai.domain.SourceType;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.repository.InvestigationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All investigation persistence for the dynamic agent, as short transactions.
 *
 * No transaction is ever held open across an LLM or MCP call: the runner calls these
 * methods between network operations. Every write is a legal transition from RUNNING;
 * a terminal investigation is never modified again.
 */
@Component
public class InvestigationStore {

    private static final Logger log = LoggerFactory.getLogger(InvestigationStore.class);
    static final int MAX_EVIDENCE_SUMMARY = 4000;

    private final InvestigationRepository repository;
    private final McpServerRepository servers;

    public InvestigationStore(InvestigationRepository repository, McpServerRepository servers) {
        this.repository = repository;
        this.servers = servers;
    }

    /** Current connection labels, so history shows which account each step used. */
    @Transactional(readOnly = true)
    public Map<UUID, String> serverNames() {
        Map<UUID, String> names = new java.util.HashMap<>();
        servers.findAll().forEach(server -> names.put(server.getId(), server.getName()));
        return names;
    }

    @Transactional
    public UUID createRunning(String question) {
        return repository.save(new Investigation(question)).getId();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findRunning(String question) {
        return repository.findFirstByQuestionAndStatusOrderByCreatedAtDesc(question, InvestigationStatus.RUNNING)
                .map(Investigation::getId);
    }

    /** Persists one finished step immediately, so progress is visible while the run continues. */
    @Transactional
    public void appendStep(UUID id, AgentExecutionStep step) {
        Investigation investigation = find(id);
        if (!investigation.isRunning()) {
            return;
        }
        boolean alreadyRecorded = investigation.getSteps().stream()
                .anyMatch(existing -> existing.getStepNumber() == step.stepNumber());
        if (!alreadyRecorded) {
            investigation.addStep(InvestigationStep.from(step));
        }
    }

    /**
     * Writes the terminal state from the finished execution. Steps are rewritten from the
     * execution state (the source of truth), so a missed progress write cannot leave gaps.
     */
    @Transactional
    public void complete(UUID id, AgentExecutionState execution, GroundedAnswer answer, java.util.Map<UUID, String> serverNames) {
        Investigation investigation = find(id);
        if (!investigation.isRunning()) {
            log.warn("Ignoring completion of investigation {} which is already {}", id, investigation.getStatus());
            return;
        }

        investigation.replaceSteps(execution.steps().stream().map(InvestigationStep::from).toList());
        investigation.replaceEvidence(answer.evidence().stream().map(e -> toEvidence(e, serverNames.get(e.serverId()))).toList());

        InvestigationStatus status = statusFor(execution, answer);
        investigation.finish(status, answer.answer(), execution.stopReason());
        log.info("Investigation finished: id={}, status={}, executionStatus={}, steps={}, evidence={}, stopReason={}",
                id, status, execution.status(), execution.steps().size(), answer.evidence().size(), execution.stopReason());
    }

    @Transactional
    public void fail(UUID id, String reason) {
        repository.findById(id).ifPresent(investigation -> {
            if (investigation.isRunning()) {
                investigation.markFailed(reason);
            }
        });
    }

    /** Fails every investigation left RUNNING, e.g. by a restart mid-run. */
    @Transactional
    public int failAbandoned(String reason) {
        List<Investigation> running = repository.findByStatus(InvestigationStatus.RUNNING);
        running.forEach(investigation -> investigation.markFailed(reason));
        return running.size();
    }

    @Transactional(readOnly = true)
    public InvestigationResponse load(UUID id) {
        return InvestigationResponse.from(find(id), serverNames());
    }

    /**
     * ANSWERED needs a completed run whose answer is grounded in evidence. Evidence from a run
     * that stopped or failed later is PARTIAL. No evidence is INCONCLUSIVE when the run ended
     * normally, and FAILED when it could not be carried out.
     */
    static InvestigationStatus statusFor(AgentExecutionState execution, GroundedAnswer answer) {
        boolean grounded = answer.grounded() && !answer.evidence().isEmpty();
        if (grounded && answer.coverage() != GroundedAnswer.Coverage.NONE) {
            // Only a verified model answer that claims full coverage of a completed run is ANSWERED.
            return answer.coverage() == GroundedAnswer.Coverage.FULL
                    && execution.status() == AgentExecutionStatus.COMPLETED
                    ? InvestigationStatus.ANSWERED
                    : InvestigationStatus.PARTIAL;
        }
        boolean everyStepFailed = !execution.steps().isEmpty() && !execution.hasSuccessfulStep();
        return execution.status() == AgentExecutionStatus.FAILED || everyStepFailed
                ? InvestigationStatus.FAILED
                : InvestigationStatus.INCONCLUSIVE;
    }

    private static EvidenceItem toEvidence(GroundedEvidence evidence, String serverName) {
        String summary = evidence.content();
        if (summary.length() > MAX_EVIDENCE_SUMMARY) {
            summary = summary.substring(0, MAX_EVIDENCE_SUMMARY - 3) + "...";
        }
        return new EvidenceItem(
                sourceTypeFor((serverName == null ? "" : serverName) + " " + evidence.toolName()),
                evidence.citation(),
                summary,
                evidence.stepNumber(),
                evidence.toolName(),
                evidence.serverId());
    }

    /** Classifies evidence by the owning server's name and the tool name, e.g. "GitHub - Main get_me". */
    static SourceType sourceTypeFor(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("github")) return SourceType.GITHUB;
        if (name.contains("jira")) return SourceType.JIRA;
        if (name.contains("splunk")) return SourceType.SPLUNK;
        if (name.contains("slack")) return SourceType.SLACK;
        if (name.contains("queue")) return SourceType.QUEUE;
        return SourceType.MCP;
    }

    private Investigation find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Investigation " + id + " not found"));
    }
}
