package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.model.Role;
import com.finagent.model.User;
import com.finagent.repository.UserRepository;
import com.finagent.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 16: registration / login / profile over the {@link User} store.
 *
 * <p>Security rules: emails normalized (trim + lower-case) so uniqueness is
 * case-insensitive; passwords validated (length + letter/digit) and hashed
 * with BCrypt — NEVER stored or returned in plaintext, NEVER logged;
 * registration always assigns {@link Role#USER} (self-registering ADMIN is
 * impossible — no role field exists on the request); login failures return a
 * generic message that reveals nothing about email existence.</p>
 */
@Service
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class AuthService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    static final int PASSWORD_MIN_LENGTH = 8;
    /** BCrypt truncates beyond 72 bytes — reject longer passwords explicitly. */
    static final int PASSWORD_MAX_LENGTH = 72;

    private final UserRepository users;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public AuthService(UserRepository users, JwtService jwtService, RefreshTokenService refreshTokens,
                       FinAgentProperties properties, AuditService audit) {
        this.users = users;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = new BCryptPasswordEncoder(properties.getAuth().getBcryptStrength());
        this.audit = audit;
    }

    /** Register a new USER. Throws {@link DuplicateEmailException} on conflict. */
    @Transactional
    public AuthResult register(String email, String password) {
        String normalized = normalizeEmail(email);
        validatePassword(password);
        if (users.existsByEmail(normalized)) {
            log.warn("Registration rejected: email already registered");
            audit.record(AuditEventType.AUTH_REGISTER, null, AuditEvent.Result.FAILURE,
                    AuditService.valueMetadata("emailHash", AuditService.emailHash(normalized)));
            throw new DuplicateEmailException();
        }
        User user = new User(normalized, passwordEncoder.encode(password), Role.USER);
        users.save(user);
        log.info("User registered: id={}", user.getId());
        audit.record(AuditEventType.AUTH_REGISTER, user.getId(), AuditEvent.Result.SUCCESS,
                AuditService.idMetadata("userId", user.getId()));
        return toAuthResult(user);
    }

    /** Authenticate and issue a token. Generic failure — never reveals email existence. */
    // Task 2: read-write (not read-only): successful login persists a refresh token.
    @Transactional
    public AuthResult login(String email, String password) {
        String normalized = normalizeEmail(email);
        User user = users.findByEmail(normalized).orElse(null);
        if (user == null || password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // Same path, same message, no timing oracle beyond BCrypt itself.
            // Attribution by email hash only — the address itself is not stored.
            log.warn("Failed login attempt");
            audit.record(AuditEventType.AUTH_LOGIN_FAILURE, null, AuditEvent.Result.FAILURE,
                    AuditService.valueMetadata("emailHash", AuditService.emailHash(normalized)));
            throw new BadCredentialsException();
        }
        log.info("User logged in: id={}", user.getId());
        audit.record(AuditEventType.AUTH_LOGIN_SUCCESS, user.getId(), AuditEvent.Result.SUCCESS,
                AuditService.idMetadata("userId", user.getId()));
        return toAuthResult(user);
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return users.findById(id).orElseThrow(BadCredentialsException::new);
    }

    /**
     * Task 2: consume one refresh token and return a fresh token pair. The access
     * JWT is minted here (claims unchanged); rotation itself lives in
     * {@link RefreshTokenService}. Failures are generic (see that service).
     */
    // No rollback on business rejections: rotate() joins this transaction, and
    // only the outermost definition governs rollback — the reuse revocation it
    // performs must commit (see RefreshTokenService.rotate).
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResult refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        User user = rotation.user();
        String accessToken = jwtService.createToken(user.getId(), user.getEmail(), user.getRole().name());
        return new AuthResult(accessToken, jwtService.expiresInSeconds(),
                rotation.refreshToken(), rotation.refreshExpiresIn(),
                user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    /**
     * Task 2: revoke the session chain of the presented refresh token.
     * Idempotent — unknown tokens succeed silently (no validity oracle).
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.logout(rawRefreshToken);
    }

    private AuthResult toAuthResult(User user) {
        String token = jwtService.createToken(user.getId(), user.getEmail(), user.getRole().name());
        RefreshTokenService.IssuedToken refresh = refreshTokens.issue(user);
        return new AuthResult(token, jwtService.expiresInSeconds(),
                refresh.refreshToken(), refresh.refreshExpiresIn(),
                user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    static String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
        return normalized;
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH
                || password.length() > PASSWORD_MAX_LENGTH
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            throw new IllegalArgumentException(
                    "Password must be 8-72 characters and contain at least one letter and one digit");
        }
    }

    /** Token pair + public user view. Carries no password material. */
    public record AuthResult(String token, long expiresIn, String refreshToken, long refreshExpiresIn,
                             UUID userId, String email, Role role, java.time.Instant createdAt) {
    }
}
