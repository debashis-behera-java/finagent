package com.finagent.exception;

/**
 * Thrown when an upstream provider does not respond within the configured timeout
 * (after retries are exhausted). Mapped to HTTP 504 by {@link GlobalExceptionHandler}.
 */
public class ProviderTimeoutException extends ProviderException {

    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
