package com.relay.infrastructure.llm;

/** Every key is burned or cooling down. The router turns this into a stub fallback. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }
}
