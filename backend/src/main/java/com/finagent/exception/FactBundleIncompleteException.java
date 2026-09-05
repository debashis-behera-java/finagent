package com.finagent.exception;

/**
 * Thrown when a research run cannot assemble any usable facts — every ticker failed at
 * every data source, so there is nothing for the LLM to interpret and nothing honest
 * to report. Mapped to HTTP 502 by {@link GlobalExceptionHandler} (upstream failure).
 */
public class FactBundleIncompleteException extends RuntimeException {

    public FactBundleIncompleteException(String message) {
        super(message);
    }

    public FactBundleIncompleteException(String message, Throwable cause) {
        super(message, cause);
    }
}
