package com.connectai.service;

import com.connectai.agent.AgentOrchestrator;
import com.connectai.agent.AgentResult;
import com.connectai.agent.Finding;
import com.connectai.api.dto.EvidenceResponse;
import com.connectai.api.dto.InvestigationResponse;
import com.connectai.api.dto.InvestigationSummary;
import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.repository.InvestigationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class InvestigationService {

    private final InvestigationRepository investigations;
    private final AgentOrchestrator agent;

    public InvestigationService(InvestigationRepository investigations, AgentOrchestrator agent) {
        this.investigations = investigations;
        this.agent = agent;
    }

    /**
     * Runs the agent synchronously and stores the outcome.
     * Fine while tools are instant; once real APIs make it slow we will save RUNNING first
     * and finish in the background.
     */
    @Transactional
    public InvestigationResponse create(String question) {
        Investigation investigation = new Investigation(question.trim());
        try {
            AgentResult result = agent.investigate(investigation.getQuestion());
            for (Finding f : result.evidence()) {
                investigation.addEvidence(new EvidenceItem(f.source(), f.sourceRef(), f.summary(), f.confidence()));
            }
            investigation.markAnswered(result.answer());
        } catch (RuntimeException e) {
            investigation.markFailed("Investigation failed: " + e.getMessage());
        }
        return InvestigationResponse.from(investigations.save(investigation));
    }

    @Transactional(readOnly = true)
    public InvestigationResponse get(Long id) {
        return InvestigationResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<InvestigationSummary> listRecent() {
        return investigations.findTop20ByOrderByCreatedAtDesc().stream()
                .map(InvestigationSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EvidenceResponse> getEvidence(Long id) {
        return find(id).getEvidenceItems().stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    private Investigation find(Long id) {
        return investigations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Investigation " + id + " not found"));
    }
}
