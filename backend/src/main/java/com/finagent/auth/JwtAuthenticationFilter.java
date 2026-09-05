package com.finagent.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Phase 16: stateless Bearer-token authentication. Validates the JWT signature
 * + expiry (no DB hit for that), then reloads the principal to confirm the
 * account still exists. Any failure leaves the request unauthenticated — the
 * security chain then returns 401 for protected paths.
 *
 * <p>{@link UserDetailsService} is injected leniently: in the DB-less dev
 * profile the bean is absent and every token is simply unauthenticated
 * (dev permits all API access anyway — documented tradeoff).</p>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ObjectProvider<UserDetailsService> userDetailsServices;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   ObjectProvider<UserDetailsService> userDetailsServices) {
        this.jwtService = jwtService;
        this.userDetailsServices = userDetailsServices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = extractBearerToken(request);
            if (token != null) {
                authenticate(token);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Stateless JWT must ALSO authenticate async dispatches: {@code OncePerRequestFilter}
     * skips them by default, and the async re-dispatch (e.g. the MCP server's SSE
     * streaming) would otherwise arrive anonymous and fail authorization — breaking
     * the already-committed response stream. Re-validating the Bearer token (present
     * on every dispatch of the same request) is cheap and keeps each dispatch
     * self-sufficient, with no reliance on cross-thread context propagation.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private void authenticate(String token) {
        final Claims claims;
        try {
            claims = jwtService.parseToken(token);
        } catch (InvalidTokenException ex) {
            log.debug("Rejecting request with invalid access token: {}", ex.getMessage());
            return;
        }
        UserDetailsService service = userDetailsServices.getIfAvailable();
        if (service == null) {
            return;
        }
        final UserDetails user;
        try {
            user = service.loadUserByUsername(claims.getSubject());
        } catch (UsernameNotFoundException ex) {
            log.debug("Access token for unknown/deleted user");
            return;
        }
        // Role comes from the live user record, not the token: revoking or
        // downgrading a role takes effect without waiting for token expiry.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
