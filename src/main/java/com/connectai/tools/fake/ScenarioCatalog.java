package com.connectai.tools.fake;

import com.connectai.agent.Finding;
import com.connectai.domain.SourceType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canned evidence for three demo scenarios, so the whole agent can be exercised with no external systems.
 * The first scenario whose keyword appears in the question wins, so specific ones come first.
 */
public class ScenarioCatalog {

    private record Scenario(List<String> keywords, Map<SourceType, List<Finding>> findings) {
    }

    private final List<Scenario> scenarios = List.of(orders(), payment(), checkout());

    public List<Finding> findingsFor(String question, SourceType source) {
        String q = question.toLowerCase(Locale.ROOT);
        return scenarios.stream()
                .filter(s -> s.keywords().stream().anyMatch(q::contains))
                .findFirst()
                .map(s -> s.findings().getOrDefault(source, List.of()))
                .orElse(List.of());
    }

    private static Scenario orders() {
        return new Scenario(List.of("order", "stuck"), Map.of(
                SourceType.GITHUB, List.of(new Finding(SourceType.GITHUB, "PR #892 / commit def456",
                        "payment-service v3.2 added a required 'currency' field to the payment.completed event "
                                + "(schema v2 to v3). Linked ticket: ORDER-4821.", 0.93)),
                SourceType.JIRA, List.of(new Finding(SourceType.JIRA, "ORDER-4821",
                        "Orders stuck in Payment Processing: 340 customers were charged but their order status never updated.", 0.97)),
                SourceType.SPLUNK, List.of(new Finding(SourceType.SPLUNK, "order-service logs",
                        "SchemaValidationException: unrecognized field 'currency', event sent to the dead-letter queue. "
                                + "Seen 1,240 times since the v3.2 deploy, zero before.", 0.95)),
                SourceType.SLACK, List.of(new Finding(SourceType.SLACK, "#payments-eng thread",
                        "Someone asked whether the event schema was bumped because order-service is rejecting "
                                + "payment.completed events. No reply yet.", 0.74))
        ));
    }

    private static Scenario payment() {
        return new Scenario(List.of("payment"), Map.of(
                SourceType.GITHUB, List.of(new Finding(SourceType.GITHUB, "PR #456 / commit abc123",
                        "Reduced the retry window in PaymentService.java from 30s to 10s as part of release 2.4.1. "
                                + "Linked ticket: JIRA-123.", 0.96)),
                SourceType.JIRA, List.of(new Finding(SourceType.JIRA, "JIRA-123",
                        "Speed up payment retries, requested by support after slow-checkout complaints.", 0.91)),
                SourceType.SPLUNK, List.of(new Finding(SourceType.SPLUNK, "PaymentException spike",
                        "PaymentException error rate rose 340% in the 40 minutes after the release 2.4.1 deployment.", 0.98)),
                SourceType.SLACK, List.of(new Finding(SourceType.SLACK, "#incidents thread",
                        "An engineer reported payment timeouts spiking shortly after the release finished rolling out.", 0.78))
        ));
    }

    private static Scenario checkout() {
        return new Scenario(List.of("checkout", "changed"), Map.of(
                SourceType.GITHUB, List.of(new Finding(SourceType.GITHUB, "3 commits in checkout-service",
                        "Coupon validation rewritten (JIRA-144), cart rounding fixed (JIRA-141), "
                                + "payment-sdk bumped from 2.1 to 2.3 with no ticket.", 0.94)),
                SourceType.JIRA, List.of(new Finding(SourceType.JIRA, "JIRA-141, JIRA-144",
                        "Both tickets closed this week: 'Fix rounding on multi-item carts' and 'Support stacked coupons'.", 0.88))
        ));
    }
}
