package com.finagent.auth;

/**
 * Phase 16: authentication failure. The message is intentionally generic —
 * callers must not reveal whether the email exists.
 */
public class BadCredentialsException extends RuntimeException {

    public BadCredentialsException() {
        super("Invalid email or password");
    }
}
