package com.connectai.agent;

/** Planner failure with a client-safe category and optional provider retry hint. */
public final class PlannerFailureException extends RuntimeException {
    private final AgentFailureCategory category;
    private final Long retryAfterSeconds;

    public PlannerFailureException(AgentFailureCategory category, String safeMessage, Long retryAfterSeconds) {
        super(safeMessage);
        if (category != AgentFailureCategory.PLANNER_FAILURE
                && category != AgentFailureCategory.PROVIDER_FAILURE
                && category != AgentFailureCategory.PROVIDER_RATE_LIMIT) {
            throw new IllegalArgumentException("Planner failure category must describe a planner/provider failure.");
        }
        this.category = category;
        this.retryAfterSeconds = retryAfterSeconds == null || retryAfterSeconds < 0 ? null : retryAfterSeconds;
    }

    public AgentFailureCategory category() { return category; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
