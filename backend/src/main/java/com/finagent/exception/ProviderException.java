package com.finagent.exception;

/**
 * Thrown when an upstream data provider (market data, news) fails.
 * Mapped to HTTP 502 by {@link GlobalExceptionHandler}.
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
