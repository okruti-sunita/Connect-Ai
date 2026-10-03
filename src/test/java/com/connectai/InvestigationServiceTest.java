package com.connectai;

import com.connectai.api.dto.InvestigationResponse;
import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.domain.SourceType;
import com.connectai.repository.InvestigationRepository;
import com.connectai.service.InvestigationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "connectai.seed-demo-data=false")
class InvestigationServiceTest {

    @Autowired
    private InvestigationService service;

    @Autowired
    private InvestigationRepository repository;

    @Test
    void evidenceIsPersistedAndReturnedWithTheInvestigation() {
        Investigation inv = new Investigation("Why is checkout slow?");
        inv.addEvidence(new EvidenceItem(SourceType.GITHUB, "PR #1", "Changed a timeout", 0.9));
        inv.addEvidence(new EvidenceItem(SourceType.JIRA, "JIRA-1", "Ticket text", 0.8));
        Investigation saved = repository.save(inv);

        InvestigationResponse response = service.get(saved.getId());

        assertThat(response.evidence()).hasSize(2);
        assertThat(response.evidence().get(0).sourceRef()).isEqualTo("PR #1");
        assertThat(service.getEvidence(saved.getId())).hasSize(2);
    }
}
