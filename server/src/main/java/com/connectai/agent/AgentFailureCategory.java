package com.connectai.agent;

/** Safe, stable classification for why an investigation execution stopped. */
public enum AgentFailureCategory {
    NONE,
    PLANNER_FAILURE,
    PROVIDER_FAILURE,
    PROVIDER_RATE_LIMIT
}
