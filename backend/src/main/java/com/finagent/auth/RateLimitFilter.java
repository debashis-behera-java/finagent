package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Phase 16: rate-limiting filter for expensive endpoints.
 *
 * <p>Covers {@code POST /api/v1/research} (LLM-costly job creation). Auth
 * endpoints enforce their own budget inside {@code AuthController} (checked
 * before BCrypt). Rejections are 429 JSON via
 * {@link RateLimitExceededException} so the shape matches {@code ApiError}.</p>
 *
 * <p>Absent when auth is disabled (DB-less dev profile).</p>
 */
@Component
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimit;
    private final boolean enabled;

    public RateLimitFilter(RateLimitService rateLimit, FinAgentProperties properties) {
        this.rateLimit = rateLimit;
        this.enabled = properties.getAuth().isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (enabled && isLimited(request)
                && !rateLimit.tryAcquire(clientIp(request), RateLimitService.Group.RESEARCH)) {
            // Written here, not thrown: filter exceptions never reach
            // RestControllerAdvice, so the 429 JSON is rendered directly
            // (same ApiError shape as the controller path).
            log.warn("Research rate limit exceeded");
            com.finagent.config.SecurityFailureHandlers.write429(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isLimited(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/research".equals(request.getRequestURI());
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }
}
