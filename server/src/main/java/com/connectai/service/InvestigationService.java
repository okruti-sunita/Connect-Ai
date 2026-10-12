package com.connectai.service;

import com.connectai.agent.AgentOrchestrator;
import com.connectai.agent.AgentResult;
import com.connectai.agent.Finding;
import com.connectai.api.dto.EvidenceResponse;
import com.connectai.api.dto.InvestigationResponse;
import com.connectai.api.dto.InvestigationSummary;
import com.connectai.api.dto.PageResponse;
import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.repository.InvestigationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_PAGE_SIZE = 50;

    private final InvestigationRepository investigations;
    private final AgentOrchestrator agent;
    private final InvestigationStore store;
    private final InvestigationRunner runner;
    private final TaskExecutor taskExecutor;
    private final String toolsMode;
    private final Object submitLock = new Object();

    public InvestigationService(
            InvestigationRepository investigations,
            AgentOrchestrator agent,
            InvestigationStore store,
            InvestigationRunner runner,
            @Qualifier("investigationTaskExecutor") TaskExecutor taskExecutor,
            @Value("${connectai.tools.mode:mcp}") String toolsMode) {
        this.investigations = investigations;
        this.agent = agent;
        this.store = store;
        this.runner = runner;
        this.taskExecutor = taskExecutor;
        this.toolsMode = toolsMode;
    }

    /**
     * MCP mode: persists the investigation as RUNNING, schedules the agent run and returns
     * immediately; clients poll GET /api/investigations/{id} until a terminal status.
     * Submitting a question identical to one that is still RUNNING returns that run
     * instead of starting a duplicate.
     *
     * Fake/demo mode keeps the original synchronous behaviour (the demo tools are instant).
     */
    public InvestigationResponse create(String question) {
        String trimmed = question.trim();
        if (!"mcp".equalsIgnoreCase(toolsMode)) {
            return createWithDemoTools(trimmed);
        }

        UUID id;
        synchronized (submitLock) {
            var existing = store.findRunning(trimmed);
            if (existing.isPresent()) {
                log.info("Duplicate submission of a running investigation; returning id={}", existing.get());
                return store.load(existing.get());
            }
            id = store.createRunning(trimmed);
        }
        log.info("Investigation accepted: id={}, questionLength={}", id, trimmed.length());

        try {
            taskExecutor.execute(() -> runner.run(id, trimmed));
        } catch (TaskRejectedException rejected) {
            log.warn("Investigation queue is full; rejecting id={}", id);
            store.fail(id, "The server is busy running other investigations. Try again shortly.");
        }
        return store.load(id);
    }

    /** Deterministic legacy path for explicitly selected fake/demo mode. */
    private InvestigationResponse createWithDemoTools(String question) {
        Investigation investigation = new Investigation(question);
        try {
            AgentResult result = agent.investigate(investigation.getQuestion());
            for (Finding f : result.evidence()) {
                investigation.addEvidence(new EvidenceItem(f.source(), f.sourceRef(), f.summary(), f.confidence()));
            }
            investigation.markAnswered(result.answer());
        } catch (RuntimeException e) {
            log.error("Investigation failed: cause={}", e.toString(), e);
            investigation.markFailed("Investigation failed: " + safeMessage(e));
        }
        return InvestigationResponse.from(investigations.save(investigation));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Transactional(readOnly = true)
    public InvestigationResponse get(UUID id) {
        return InvestigationResponse.from(find(id), store.serverNames());
    }

    /**
     * Filtered, paged list for the Investigations screen, newest first.
     * Every filter is optional; page is zero-based and size is capped at {@value #MAX_PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public PageResponse<InvestigationSummary> search(
            InvestigationStatus status, Boolean archived, String q, int page, int size) {

        Specification<Investigation> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (archived != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("archived"), archived));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.<String>get("question")), pattern, '\\'));
        }

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Investigation> result = investigations.findAll(spec, pageable);

        return new PageResponse<>(
                result.getContent().stream().map(InvestigationSummary::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** Makes %, _ and backslash match literally inside a LIKE pattern. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public List<InvestigationSummary> listRecent() {
        return investigations.findTop20ByOrderByCreatedAtDesc().stream()
                .map(InvestigationSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EvidenceResponse> getEvidence(UUID id) {
        return find(id).getEvidenceItems().stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    private Investigation find(UUID id) {
        return (Investigation) investigations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Investigation " + id + " not found"));
    }
}
