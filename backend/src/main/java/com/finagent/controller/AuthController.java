package com.finagent.controller;

import com.finagent.auth.AuthService;
import com.finagent.auth.BadCredentialsException;
import com.finagent.auth.RateLimitExceededException;
import com.finagent.auth.RateLimitService;
import com.finagent.dto.request.LoginRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
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

    public AuthController(AuthService authService, RateLimitService rateLimit) {
        this.authService = authService;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new USER account (always role USER)")
    public ResponseEntity<AuthResponseDto> register(@Valid @RequestBody RegisterRequest request,
                                                    HttpServletRequest http) {
        checkRateLimit(http);
        AuthService.AuthResult result = authService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive a short-lived JWT access token")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest http) {
        checkRateLimit(http);
        AuthService.AuthResult result = authService.login(request.email(), request.password());
        return ResponseEntity.ok(toResponse(result));
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

    private static AuthResponseDto toResponse(AuthService.AuthResult result) {
        return AuthResponseDto.bearer(result.token(), result.expiresIn(),
                new UserDto(result.userId(), result.email(), result.role(), result.createdAt()));
    }
}
