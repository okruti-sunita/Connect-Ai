package com.connectai.llm;

/** HTTP failure returned by a model provider, without preserving its response body. */
public final class LlmProviderException extends LlmException {
    private final int statusCode;
    private final Long retryAfterSeconds;

    public LlmProviderException(int statusCode, Long retryAfterSeconds) {
        super(safeMessage(statusCode, retryAfterSeconds));
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds == null || retryAfterSeconds < 0 ? null : retryAfterSeconds;
    }

    public int statusCode() { return statusCode; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }

    private static String safeMessage(int statusCode, Long retryAfterSeconds) {
        if (statusCode == 429) {
            return "LLM provider rate limit (HTTP 429)"
                    + (retryAfterSeconds == null ? "." : ". Retry after " + retryAfterSeconds + " seconds.");
        }
        return "LLM provider request failed (HTTP " + statusCode + ").";
    }
}
