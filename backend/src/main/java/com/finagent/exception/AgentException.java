package com.finagent.exception;

/**
 * Thrown when the AI research agent fails at the LLM stage (synthesis call fails or
 * returns no usable prose). Mapped to HTTP 500 by {@link GlobalExceptionHandler}.
 * Data-collection failures are NOT wrapped here — they become FactBundle data gaps
 * (degraded run) or {@link FactBundleIncompleteException} (total failure).
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
