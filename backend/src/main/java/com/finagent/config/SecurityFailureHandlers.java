package com.finagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Phase 16: safe security-failure responses. Filter-chain rejections never reach
 * {@code GlobalExceptionHandler}, so 401/403 are rendered here as the same
 * {@link ApiError} JSON shape — no stack traces, class names, paths beyond the
 * request URI, or credential material.
 */
public final class SecurityFailureHandlers {

    private SecurityFailureHandlers() {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    /** 401 — missing or invalid credentials. */
    @Component
    @Slf4j
    public static class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException ex) throws IOException {
            log.debug("401 {}: {}", request.getRequestURI(), ex.getMessage());
            write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                    "Authentication required — provide a valid Bearer access token");
        }
    }

    /** 403 — authenticated but not allowed (incl. disabled Swagger, wrong role). */
    @Component
    @Slf4j
    public static class JsonAccessDeniedHandler implements AccessDeniedHandler {
        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException ex) throws IOException {
            log.debug("403 {}: {}", request.getRequestURI(), ex.getMessage());
            write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                    "You do not have permission to access this resource");
        }
    }

    /** 429 — rate budget exhausted (used by the rate-limit filter directly). */
    public static void write429(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                "Too many requests — please slow down and try again");
    }

    static void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String error, String message) throws IOException {
        ApiError body = new ApiError(Instant.now(), status.value(), error, message,
                request.getRequestURI(), List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }
}
