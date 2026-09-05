package com.finagent.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Phase 16: rate budget exhausted. Mapped to HTTP 429 with a generic message.
 * The {@code ResponseStatus} also covers the filter path (thrown before the
 * DispatcherServlet, where {@code RestControllerAdvice} cannot apply).
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        super("Too many requests — please slow down and try again");
    }
}
