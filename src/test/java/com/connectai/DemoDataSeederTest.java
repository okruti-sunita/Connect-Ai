package com.connectai;

import com.connectai.api.dto.InvestigationSummary;
import com.connectai.service.InvestigationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DemoDataSeederTest {

    @Autowired
    private InvestigationService service;

    @Test
    void demoInvestigationIsSeededWithFourEvidenceItems() {
        List<InvestigationSummary> all = service.listRecent();

        assertThat(all).anyMatch(s ->
                s.question().contains("release 2.4.1") && s.evidenceCount() == 4);
    }
}
