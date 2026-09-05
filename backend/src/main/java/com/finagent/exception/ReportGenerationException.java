package com.finagent.exception;

/**
 * Thrown when PDF report generation itself fails (PDFBox I/O, malformed stored
 * data). Mapped to a safe HTTP 500 by {@link GlobalExceptionHandler} — the cause
 * is logged server-side with the research id, never exposed to the client.
 */
public class ReportGenerationException extends RuntimeException {

    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
