package com.finagent.exception;

import java.time.Instant;
import java.util.List;

/**
 * Canonical error shape returned by every endpoint on failure.
 *
 * <pre>{@code
 * {
 *   "timestamp": "2026-01-01T12:00:00Z",
 *   "status": 400,
 *   "error": "Validation Failed",
 *   "message": "Request validation failed",
 *   "path": "/api/v1/...",
 *   "fieldErrors": [ { "field": "name", "message": "must not be blank" } ]
 * }
 * }</pre>
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldValidationError> fieldErrors
) {

    public record FieldValidationError(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }
}
