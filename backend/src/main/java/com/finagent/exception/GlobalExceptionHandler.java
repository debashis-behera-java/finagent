package com.finagent.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Global exception handler: every error leaves the API as a consistent {@link ApiError} JSON body.
 * Controllers never implement their own error handling.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("404 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldValidationError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError body = new ApiError(
                java.time.Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Request validation failed",
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "Malformed request body", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "Invalid value for parameter '%s'".formatted(ex.getName()), request.getRequestURI()));
    }

    @ExceptionHandler(ProviderTimeoutException.class)
    public ResponseEntity<ApiError> handleProviderTimeout(ProviderTimeoutException ex, HttpServletRequest request) {
        log.warn("504 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ApiError.of(HttpStatus.GATEWAY_TIMEOUT.value(), "Gateway Timeout",
                        "Upstream data provider timed out", request.getRequestURI()));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiError> handleProvider(ProviderException ex, HttpServletRequest request) {
        log.warn("502 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(HttpStatus.BAD_GATEWAY.value(), "Bad Gateway",
                        "Upstream data provider failed", request.getRequestURI()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldValidationError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldValidationError(
                        v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ApiError body = new ApiError(
                java.time.Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Request parameter validation failed",
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(FactBundleIncompleteException.class)
    public ResponseEntity<ApiError> handleFactBundleIncomplete(FactBundleIncompleteException ex,
                                                               HttpServletRequest request) {
        log.warn("502 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(HttpStatus.BAD_GATEWAY.value(), "Bad Gateway",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ApiError> handleAgent(AgentException ex, HttpServletRequest request) {
        log.error("500 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.internalServerError()
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ReportNotReadyException.class)
    public ResponseEntity<ApiError> handleReportNotReady(ReportNotReadyException ex, HttpServletRequest request) {
        log.warn("409 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ReportGenerationException.class)
    public ResponseEntity<ApiError> handleReportGeneration(ReportGenerationException ex, HttpServletRequest request) {
        log.error("500 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.internalServerError()
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        "Report generation failed", request.getRequestURI()));
    }

    // ---- Phase 16: authentication boundary (safe, generic messages) ----

    @ExceptionHandler(com.finagent.auth.DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(com.finagent.auth.DuplicateEmailException ex,
                                                         HttpServletRequest request) {
        log.warn("409 {}: duplicate registration", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(com.finagent.auth.BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(com.finagent.auth.BadCredentialsException ex,
                                                         HttpServletRequest request) {
        // Generic by design: never reveals whether the email exists.
        log.warn("401 {}: failed authentication", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(com.finagent.auth.InvalidTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(com.finagent.auth.InvalidTokenException ex,
                                                       HttpServletRequest request) {
        // Generic by design: refresh failures never reveal whether a token
        // exists, expired, was revoked, or was reused (reuse is signaled
        // internally via the audit trail, not the response).
        log.warn("401 {}: invalid credential", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(com.finagent.auth.RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(com.finagent.auth.RateLimitExceededException ex,
                                                    HttpServletRequest request) {
        log.warn("429 {}: rate limit exceeded", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests",
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} ", request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        "An unexpected error occurred", request.getRequestURI()));
    }
}
