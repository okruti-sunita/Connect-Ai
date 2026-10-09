/*
 * TEMPORARY sample data so the UI renders before the backend exists.
 * Delete this file once api.ts talks to the real API.
 */
import type { InvestigationDetailData, InvestigationSummary } from "./types";

export const INVESTIGATIONS: InvestigationSummary[] = [
    { id: "inv-1", title: "Checkout latency spike after deploy #4213", status: "resolved", sources: ["github", "jira", "grafana"], owner: "MS", updatedAgo: "2h ago" },
    { id: "inv-2", title: "Payment webhook 500s from Stripe", status: "in-progress", sources: ["github", "jira", "grafana"], owner: "MS", updatedAgo: "1d ago" },
    { id: "inv-3", title: "Kafka consumer lag on orders-events", status: "archived", sources: ["github", "jira", "grafana"], owner: "JP", updatedAgo: "3d ago" },
    { id: "inv-4", title: "Login flow 2FA failing for enterprise tenant", status: "resolved", sources: ["github", "jira", "grafana"], owner: "RK", updatedAgo: "3d ago" }
];

export const buildDetail = (s: InvestigationSummary): InvestigationDetailData => ({
    id: s.id,
    title: s.title,
    status: s.status,
    startedAgo: s.updatedAgo,
    startedBy: "Sunita",
    tags: [
        { label: "JIRA-123", accent: true },
        { label: "PR #8821", accent: true },
        { label: "commit abc123", accent: true },
        { label: "payment-service v2.4.1" },
        { label: "release 2.4.1" },
        { label: "checkout-service" }
    ],
    steps: [
        { id: "s1", source: "github", text: "recent commits across payment-service, checkout-service" },
        { id: "s2", source: "jira", text: "fetching JIRA-123 and linked incidents" },
        { id: "s3", source: "grafana", text: "p95 latency for payment-service, last 24h" },
        { id: "s4", source: "splunk", text: "payment-service error logs around deploy window" },
        { id: "s5", source: "slack", text: "#payments-eng discussion, last 24h" }
    ],
    summary: {
        text:
            "Latency started climbing at 10:02 UTC, correlated with deploy #4213 which merged PR #8821 (payment-service: new retry logic). The retry loop appears to call Stripe synchronously under lock, causing thread pool saturation.",
        keyEvidence: [
            { source: "github", label: "PR #8821 • payment-service" },
            { source: "grafana", label: "p95 latency spike • 10:02 UTC" },
            { source: "jira", label: "INC-472 • similar incident Mar 14" }
        ],
        nextSteps: ["Revert PR #8821", "Add circuit breaker to Stripe client", "Open incident ticket"]
    },
    evidence: [
        {
            id: "e1", source: "github", category: "Code",
            title: "PR #8821: Add retry logic to Stripe client",
            snippet: "+ retry_policy = RetryPolicy(backoff=EXPONENTIAL) ... + stripe.charge.create(...)",
            confidence: 92, when: "2h ago", url: "https://github.com",
            detail: {
                subtitle: "payment-service • opened by @arjun-k • merged 2h ago",
                whyItMatters: "time-correlated with incident",
                description:
                    "Fixes intermittent failures in Stripe client by adding retry logic with exponential backoff. This should improve reliability during transient network issues.",
                keyChanges: [
                    "Implements resilience4j retry mechanism.",
                    "Wraps stripeClient.charge() calls with retry decorator.",
                    "Configures max attempts to 3 and initial wait time to 100ms."
                ],
                diff: [
                    { line: 34, type: "ctx", text: "public class StripePaymentProvider {" },
                    { line: 35, type: "ctx", text: "    private final StripeClient stripeClient;" },
                    { line: 36, type: "add", text: "    private final Retry retry;" },
                    { line: 37, type: "ctx", text: "" },
                    { line: 38, type: "ctx", text: "    public StripePaymentProvider(StripeClient stripeClient, RetryRegistry registry) {" },
                    { line: 39, type: "ctx", text: "        this.stripeClient = stripeClient;" },
                    { line: 40, type: "add", text: "        this.retry = registry.retry(\"stripe\");" },
                    { line: 41, type: "ctx", text: "    }" },
                    { line: 43, type: "ctx", text: "    public Charge charge(ChargeRequest request) throws PaymentException {" },
                    { line: 44, type: "add", text: "        return retry.executeSupplier(() ->" },
                    { line: 45, type: "del", text: "        stripeClient.charge(request.getAmount(), request.getCurrency())" },
                    { line: 46, type: "add", text: "            stripeClient.charge(request.getAmount(), request.getCurrency())" },
                    { line: 47, type: "add", text: "        );" },
                    { line: 48, type: "ctx", text: "    }" }
                ],
                reasoning:
                    "Connect AI selected this because: (1) merged 4 min before latency spike, (2) touches checkout path, (3) adds synchronous retry logic."
            }
        },
        { id: "e2", source: "grafana", category: "Metrics", title: "Grafana Dashboard: Payment Service Latency", snippet: "p95 latency crossed 500ms threshold at 10:02 UTC. Peak 2.3s.", confidence: 95, when: "2h ago" },
        { id: "e3", source: "jira", category: "Tickets", title: "Jira Ticket: INC-472 - Stripe timeout issues", snippet: "Similar incident resolved by increasing thread pool size last month. See resolution notes.", confidence: 85, when: "Mar 14" },
        { id: "e4", source: "splunk", category: "Logs", title: "Splunk Logs: Thread Pool Saturation Errors", snippet: "ERROR [payment-service] Thread pool exhausted. Unable to acquire lock for Stripe call.", confidence: 98, when: "2h ago" },
        { id: "e5", source: "slack", category: "Chat", title: "Slack #payments-eng: retry change discussed", snippet: "\"Retry wraps the Stripe call inside the lock, should we move it out?\" — raised 20 min before the spike.", confidence: 78, when: "2h ago" }
    ]
});
