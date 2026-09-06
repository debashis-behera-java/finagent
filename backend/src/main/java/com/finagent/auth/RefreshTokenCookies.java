package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Task 3: HttpOnly refresh-token cookie handling for the browser session flow.
 *
 * <p>The raw refresh token travels in exactly two places: the {@code Set-Cookie}
 * header the server emits at issuance/rotation, and the {@code Cookie} header
 * the browser automatically returns to {@code POST /api/v1/auth/refresh} (and
 * {@code /logout}). JavaScript can never read it ({@code HttpOnly}), so an XSS
 * payload cannot steal the long-lived credential the way it could from
 * {@code localStorage} — the residual risk moves to CSRF, which is contained
 * by {@code SameSite=Lax} plus POST-only auth endpoints (see
 * {@code docs/security.md} §8).</p>
 *
 * <p>Cookie attributes (see {@code FinAgentProperties.Auth}):</p>
 * <ul>
 *   <li>name {@code finagent_rt} — carries no secret material in the name;</li>
 *   <li>{@code HttpOnly=true} always — never readable from JavaScript;</li>
 *   <li>{@code Secure} from config — {@code true} in production, {@code false}
 *   for plain-HTTP local development (browsers drop {@code Secure} cookies
 *   over HTTP, which would break dev logins);</li>
 *   <li>{@code SameSite} from config (default {@code Lax});</li>
 *   <li>{@code Path=/api/v1/auth} — the cookie is presented only to the
 *   authentication endpoints, never to research/stocks/admin traffic;</li>
 *   <li>{@code Max-Age} equals the refresh-token TTL, so cookie expiry tracks
 *   server-side session expiry instead of inventing a second lifetime.</li>
 * </ul>
 *
 * <p>Clearing ({@code /logout}) re-emits the cookie with the same name, path,
 * {@code SameSite} and {@code Secure} configuration and {@code Max-Age=0} —
 * mismatched attributes would leave the original cookie alive in the browser.</p>
 */
@Component
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RefreshTokenCookies {

    /** Cookie name: an opaque session label, never secret material. */
    public static final String COOKIE_NAME = "finagent_rt";

    /** Restricted path: auth endpoints only. */
    public static final String COOKIE_PATH = "/api/v1/auth";

    private final FinAgentProperties properties;

    public RefreshTokenCookies(FinAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * Extract the raw refresh token the browser attached, or {@code null} when
     * the cookie is absent. Blank values are normalized to {@code null} so
     * callers treat "no cookie" uniformly.
     */
    public String readRawToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value == null || value.isBlank()) {
                    return null;
                }
                return value.trim();
            }
        }
        return null;
    }

    /** Issuance/rotation cookie: raw token, {@code Max-Age} = refresh TTL. */
    public ResponseCookie issueCookie(String rawToken, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(properties.getAuth().isCookieSecure())
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .sameSite(properties.getAuth().getCookieSameSite())
                .build();
    }

    /** Logout cookie: same name/path flags, {@code Max-Age=0} to delete. */
    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.getAuth().isCookieSecure())
                .path(COOKIE_PATH)
                .maxAge(0)
                .sameSite(properties.getAuth().getCookieSameSite())
                .build();
    }
}
