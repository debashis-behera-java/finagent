package com.finagent.auth;

/**
 * Phase 16: token errors. Carries no token material, no secrets — safe to log
 * the message and to map to a generic 401 response.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
