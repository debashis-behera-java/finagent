package com.finagent.exception;

/**
 * Thrown when a PDF report is requested for a research job that is not COMPLETED
 * (PENDING, RUNNING or FAILED). Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 * The message carries only the research id and its status — never internals.
 */
public class ReportNotReadyException extends RuntimeException {

    public ReportNotReadyException(String message) {
        super(message);
    }
}
