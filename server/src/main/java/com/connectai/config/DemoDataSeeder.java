package com.connectai.config;

import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.domain.SourceType;
import com.connectai.repository.InvestigationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Inserts the payment-failure scenario from the proposal so the API has data on first run.
 * Disable with connectai.seed-demo-data=false.
 */
@Component
@ConditionalOnProperty(name = "connectai.seed-demo-data", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements CommandLineRunner {

    static final String DEMO_QUESTION = "Why did payment failures start after release 2.4.1?";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final InvestigationRepository investigations;

    public DemoDataSeeder(InvestigationRepository investigations) {
        this.investigations = investigations;
    }

    @Override
    public void run(String... args) {
        if (investigations.existsByQuestion(DEMO_QUESTION)) {
            return;
        }

        Investigation inv = new Investigation(DEMO_QUESTION);
        inv.addEvidence(new EvidenceItem(SourceType.GITHUB, "PR #456 / commit abc123",
                "Reduced the retry window in PaymentService.java from 30s to 10s (release 2.4.1).", 0.96));
        inv.addEvidence(new EvidenceItem(SourceType.JIRA, "JIRA-123",
                "Speed up payment retries, requested by support after slow-checkout complaints.", 0.91));
        inv.addEvidence(new EvidenceItem(SourceType.SPLUNK, "PaymentException spike",
                "Error rate up 340% in the 40 minutes after the deployment.", 0.98));
        inv.addEvidence(new EvidenceItem(SourceType.SLACK, "#incidents thread",
                "Engineer reports payment timeouts spiking shortly after the release finished rolling out.", 0.78));
        inv.markAnswered("Failures line up with PR #456 (release 2.4.1), which cut the retry timeout "
                + "from 30s to 10s for JIRA-123. Splunk shows the spike starting minutes after deployment. "
                + "Suggested next step: raise the window to 20-30s or add adaptive backoff.");

        Investigation saved = investigations.save(inv);
        log.info("Seeded demo investigation with id={}", saved.getId());
    }
}
