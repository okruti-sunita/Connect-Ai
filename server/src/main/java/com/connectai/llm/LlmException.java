package com.connectai.llm;

/** Anything that goes wrong talking to the model: network, HTTP error, unusable response. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
