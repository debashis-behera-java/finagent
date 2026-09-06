package com.finagent.controller;

import com.finagent.auth.AuthService;
import com.finagent.auth.BadCredentialsException;
import com.finagent.auth.RateLimitExceededException;
import com.finagent.auth.RateLimitService;
import com.finagent.auth.RefreshTokenCookies;
import com.finagent.dto.request.LoginRequest;
import com.finagent.dto.request.RefreshRequest;
import com.finagent.dto.request.RegisterRequest;
import com.finagent.dto.response.AuthDtos.AuthResponseDto;
import com.finagent.dto.response.AuthDtos.UserDto;
import com.finagent.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 16: authentication boundary. Absent when {@code finagent.auth.enabled}
 * is false (DB-less dev profile — same tradeoff as the research endpoints).
 *
 * <p>{@code POST /register} and {@code POST /login} are public but rate
 * limited (429). {@code GET /me} requires a Bearer token. Login failures are
 * generic (never reveal email existence); passwords are never returned,
 * never logged.</p>
 *
 * <p>Task 3: browser sessions keep the refresh token in the HttpOnly
 * {@code finagent_rt} cookie ({@link RefreshTokenCookies}). Successful
 * register/login/refresh set it via {@code Set-Cookie}; refresh/logout read
 * it from the {@code Cookie} header. The JSON body NEVER carries the raw
 * refresh token ({@code refreshToken} is an explicit {@code null}) so no
 * JavaScript — and no log, error message, or persisted row — can observe it.
 * A JSON-body token is still accepted as a fallback for non-browser API
 * clients (migration path for the pre-Task-3 contract): when the cookie is
 * present it wins and the body is ignored; when both are absent the request
 * fails with the same generic 401 as any invalid token.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Tag(name = "Auth", description = "Registration, login, profile (Phase 16)")
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimit;
    private final RefreshTokenCookies cookies;

    public AuthController(AuthService authService, RateLimitService rateLimit,
                          RefreshTokenCookies cookies) {
        this.authService = authService;
        this.rateLimit = rateLimit;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new USER account (always role USER); "
            + "the refresh token is set as an HttpOnly cookie, never returned in JSON")
    public ResponseEntity<AuthResponseDto> register(@Valid @RequestBody RegisterRequest request,
                                                    HttpServletRequest http) {
        checkRateLimit(http);
        AuthService.AuthResult result = authService.register(request.email(), request.password());
        return withSessionCookie(result, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive a short-lived JWT access token; "
            + "the refresh token is set as an HttpOnly cookie, never returned in JSON")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest http) {
        checkRateLimit(http);
        AuthService.AuthResult result = authService.login(request.email(), request.password());
        return withSessionCookie(result, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh token from the HttpOnly cookie: returns a new "
            + "access JWT and sets the replacement cookie, invalidates the presented "
            + "token (single-use; replay revokes the session family). The response body "
            + "never contains the raw refresh token.")
    public ResponseEntity<AuthResponseDto> refresh(
            @CookieValue(name = RefreshTokenCookies.COOKIE_NAME, required = false) String cookieToken,
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest http) {
        checkRateLimit(http);
        // Cookie-first: browsers must use the HttpOnly path. The JSON body is a
        // fallback for non-browser API clients only — never used by the SPA.
        String raw = (cookieToken != null && !cookieToken.isBlank())
                ? cookieToken.trim()
                : (request != null ? request.refreshToken() : null);
        AuthService.AuthResult result = authService.refresh(raw);
        return withSessionCookie(result, HttpStatus.OK);
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the session family of the refresh token from the HttpOnly "
            + "cookie (idempotent; access JWTs expire on their own and cannot be revoked) "
            + "and clear the cookie")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookies.COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest http) {
        checkRateLimit(http);
        String raw = (cookieToken != null && !cookieToken.isBlank())
                ? cookieToken.trim()
                : (request != null ? request.refreshToken() : null);
        // Idempotent by design: unknown/blank tokens succeed silently (no validity
        // oracle, double-logout is normal). Validation is intentionally absent
        // here — a missing token is a successful logout, not a 400.
        authService.logout(raw);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearCookie().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Current authenticated user (requires Bearer token)")
    public UserDto me(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null) {
            throw new BadCredentialsException();
        }
        User user = authService.getById(UUID.fromString(principal.getUsername()));
        return new UserDto(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    private void checkRateLimit(HttpServletRequest http) {
        // Rate check FIRST (before BCrypt): cheap rejection, no CPU burn for attackers.
        if (!rateLimit.tryAcquire(clientIp(http), RateLimitService.Group.AUTH)) {
            log.warn("Auth rate limit exceeded");
            throw new RateLimitExceededException();
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }

    /**
     * Task 3: attach the refresh token as an HttpOnly cookie and return a body
     * that deliberately contains NO raw refresh token.
     */
    private ResponseEntity<AuthResponseDto> withSessionCookie(AuthService.AuthResult result,
                                                              HttpStatus status) {
        ResponseCookie session = cookies.issueCookie(result.refreshToken(), result.refreshExpiresIn());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, session.toString())
                .body(toResponse(result));
    }

    private static AuthResponseDto toResponse(AuthService.AuthResult result) {
        return AuthResponseDto.cookieSession(result.token(), result.expiresIn(),
                result.refreshExpiresIn(),
                new UserDto(result.userId(), result.email(), result.role(), result.createdAt()));
    }
}
