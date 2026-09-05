package com.finagent.auth;

/** Phase 16: duplicate registration. Message carries only the fact of duplication. */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("An account with this email already exists");
    }
}
