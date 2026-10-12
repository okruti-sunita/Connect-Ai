package com.connectai.service;

import com.connectai.agent.AgentOrchestrator;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.api.AgentChatResponse;
import com.connectai.agent.api.AgentChatService;
import com.connectai.agent.AgentResult;
import com.connectai.agent.Finding;
import com.connectai.api.dto.EvidenceResponse;
import com.connectai.api.dto.InvestigationResponse;
import com.connectai.api.dto.InvestigationSummary;
import com.connectai.api.dto.PageResponse;
import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.domain.InvestigationStep;
import com.connectai.domain.SourceType;
import com.connectai.repository.InvestigationRepository;
import org.springframework.beans.factory.annotation.Value;
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
    private final AgentChatService dynamicAgent;
    private final String toolsMode;

    public InvestigationService(
            InvestigationRepository investigations,
            AgentOrchestrator agent,
            AgentChatService dynamicAgent,
            @Value("${connectai.tools.mode:mcp}") String toolsMode) {
        this.investigations = investigations;
        this.agent = agent;
        this.dynamicAgent = dynamicAgent;
        this.toolsMode = toolsMode;
    }

    /**
     * Runs the agent synchronously and stores the outcome.
     * Fine while tools are instant; once real APIs make it slow we will save RUNNING first
     * and finish in the background.
     */
    @Transactional
    public InvestigationResponse create(String question) {
        Investigation investigation = new Investigation(question.trim());
        log.info("Investigation started: id={}, toolsMode={}, questionLength={}", investigation.getId(), toolsMode, question == null ? 0 : question.length());
        try {
            if ("mcp".equalsIgnoreCase(toolsMode)) {
                runDynamicInvestigation(investigation);
            } else {
                // Keep the deterministic legacy path available for explicitly selected fake/demo mode.
                AgentResult result = agent.investigate(investigation.getQuestion());
                for (Finding f : result.evidence()) {
                    investigation.addEvidence(new EvidenceItem(f.source(), f.sourceRef(), f.summary(), f.confidence()));
                }
                investigation.markAnswered(result.answer());
            }
        } catch (RuntimeException e) {
            log.error("Investigation failed: id={}, cause={}", investigation.getId(), e.toString(), e);
            investigation.markFailed("Investigation failed: " + safeMessage(e));
        }
        var saved = investigations.save(investigation);
        log.info("Investigation persisted: id={}, status={}, evidenceItems={}", saved.getId(), saved.getStatus(), saved.getEvidenceItems().size());
        return InvestigationResponse.from(saved);
    }

    /** Runs the same discovered-MCP-tool agent exposed by /api/agent/chat. */
    private void runDynamicInvestigation(Investigation investigation) {
        log.info("Investigation entering dynamic MCP agent: id={}", investigation.getId());
        AgentChatResponse result = dynamicAgent.chat(investigation.getQuestion());
        for (var step : result.steps()) {
            investigation.addStep(new InvestigationStep(step.stepNumber(), step.toolName(), step.status()));
        }
        for (var evidence : result.evidence()) {
            SourceType sourceType = sourceTypeFor(evidence.toolName());
            String summary = "[" + evidence.toolName() + "] " + evidence.content();
            if (summary.length() > 4000) {
                summary = summary.substring(0, 3997) + "...";
            }
            investigation.addEvidence(new EvidenceItem(
                    sourceType,
                    evidence.citation(),
                    summary,
                    null));
        }

        if (result.status() == AgentExecutionStatus.FAILED || !result.grounded()) {
            investigation.markFailed(result.answer());
        } else {
            investigation.markAnswered(result.answer());
        }
    }

    private static SourceType sourceTypeFor(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.contains("github")) return SourceType.GITHUB;
        if (name.contains("jira")) return SourceType.JIRA;
        if (name.contains("splunk")) return SourceType.SPLUNK;
        if (name.contains("slack")) return SourceType.SLACK;
        if (name.contains("queue")) return SourceType.QUEUE;
        return SourceType.MCP;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Transactional(readOnly = true)
    public InvestigationResponse get(UUID id) {
        return InvestigationResponse.from(find(id));
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
