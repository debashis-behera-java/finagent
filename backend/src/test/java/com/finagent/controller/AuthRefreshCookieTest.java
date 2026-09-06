package com.finagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.auth.RefreshTokenCookies;
import com.finagent.auth.RefreshTokenService;
import com.finagent.config.FinAgentProperties;
import com.finagent.model.RefreshToken;
import com.finagent.repository.RefreshTokenRepository;
import com.finagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 3: HttpOnly refresh-cookie contract on H2.
 *
 * <p>Covers the migration checklist: cookie issuance attributes (HttpOnly,
 * Path, Max-Age, SameSite, Secure-by-profile), cookie-based rotation, raw
 * token absence from bodies, logout revocation + cookie clearing, missing /
 * malformed / expired / reused rejection, hash-only persistence, CORS
 * credential behavior, and concurrent-refresh safety.</p>
 *
 * <p>Rate-limit isolation: own 10.2.x IP range, distinct from the other auth
 * test classes.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRefreshCookieTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
    private static final String GENERIC = "Invalid or expired refresh token";
    private static final String COOKIE = RefreshTokenCookies.COOKIE_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String clientIp;

    @BeforeEach
    void freshIdentity() {
        clientIp = "10.2.0." + IP_SEQ.getAndIncrement();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private String email() {
        return "cookie-" + UUID.randomUUID() + "@example.com";
    }

    private MvcResult login(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated());
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String setCookieOf(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().startsWith(COOKIE + "=");
        return setCookie;
    }

    private static String rawTokenOf(MvcResult result) {
        String setCookie = setCookieOf(result);
        String remainder = setCookie.substring((COOKIE + "=").length());
        return remainder.substring(0, remainder.indexOf(';'));
    }

    private MvcResult refreshWithCookie(String rawToken, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/auth/refresh").with(remoteIp(clientIp));
        if (rawToken != null) {
            // NB: MockMvc only exposes cookies set via the builder — a raw
            // "Cookie" header is ignored by MockHttpServletRequest.getCookies().
            builder.cookie(new jakarta.servlet.http.Cookie(COOKIE, rawToken));
        }
        return mockMvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
    }

    @Test
    void loginSetsHttpOnlyRefreshCookieWithRestrictedPath() throws Exception {
        MvcResult loggedIn = login(email());

        String setCookie = setCookieOf(loggedIn);
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Path=" + RefreshTokenCookies.COOKIE_PATH);
        assertThat(setCookie).contains("SameSite=Lax");
        // Max-Age tracks the refresh TTL (14d default = 1209600s).
        assertThat(setCookie).contains("Max-Age=1209600");
        // Plain-HTTP test profile: no Secure flag (dev must stay usable).
        assertThat(setCookie).doesNotContain("Secure");
        // Cookie name carries no secret material.
        assertThat(setCookie.split("=", 2)[0]).isEqualTo(COOKIE);
    }

    @Test
    void registerSetsRefreshCookieToo() throws Exception {
        String mail = email();
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String setCookie = setCookieOf(registered);
        assertThat(setCookie).contains("HttpOnly").contains("Path=" + RefreshTokenCookies.COOKIE_PATH);
    }

    @Test
    void productionCookieBuilderEmitsSecureFlag() {
        // Unit-level: the prod profile forces cookie-secure=true (no prod boot
        // here — that profile needs real secrets). Same builder, prod flag.
        FinAgentProperties prodLike = new FinAgentProperties();
        prodLike.getAuth().setCookieSecure(true);
        RefreshTokenCookies prodCookies = new RefreshTokenCookies(prodLike);

        String setCookie = prodCookies.issueCookie("opaque-value", 1209600).toString();

        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("Path=" + RefreshTokenCookies.COOKIE_PATH);
        assertThat(setCookie).contains("SameSite=Lax");

        String cleared = prodCookies.clearCookie().toString();
        assertThat(cleared).startsWith(COOKIE + "=");
        assertThat(cleared).contains("Secure");
        assertThat(cleared).contains("Path=" + RefreshTokenCookies.COOKIE_PATH);
        assertThat(cleared).containsAnyOf("Max-Age=0", "Max-Age=0;");
    }

    @Test
    void refreshReadsCookieRotatesAndNeverLeaksRawToken() throws Exception {
        MvcResult loggedIn = login(email());
        String first = rawTokenOf(loggedIn);
        JsonNode loginBody = objectMapper.readTree(loggedIn.getResponse().getContentAsString());
        assertThat(loginBody.path("refreshToken").isNull()).isTrue();
        assertThat(loggedIn.getResponse().getContentAsString()).doesNotContain(first);

        // Cookie-only refresh: no JSON body at all.
        MvcResult rotated = refreshWithCookie(first, 200);
        JsonNode pair = objectMapper.readTree(rotated.getResponse().getContentAsString());
        assertThat(pair.get("accessToken").asText()).isNotBlank();
        assertThat(pair.path("refreshToken").isNull()).isTrue();

        String second = rawTokenOf(rotated);
        assertThat(second).isNotBlank().isNotEqualTo(first);
        assertThat(rotated.getResponse().getContentAsString()).doesNotContain(second);

        // The rotated access token authenticates.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pair.get("accessToken").asText()))
                .andExpect(status().isOk());

        // The consumed cookie value is dead (single-use).
        refreshWithCookie(first, 401);
    }

    @Test
    void reusedCookieTokenKillsTheWholeFamily() throws Exception {
        login(email());
        MvcResult loggedIn = login(email());
        String first = rawTokenOf(loggedIn);
        String second = rawTokenOf(refreshWithCookie(first, 200));

        MvcResult replay = refreshWithCookie(first, 401);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("message").asText()).isEqualTo(GENERIC);

        // The legitimate replacement died with the family (theft response).
        refreshWithCookie(second, 401);
    }

    @Test
    void logoutRevokesFamilyAndClearsCookieWithSameAttributes() throws Exception {
        login(email());
        MvcResult loggedIn = login(email());
        String first = rawTokenOf(loggedIn);
        String second = rawTokenOf(refreshWithCookie(first, 200));

        MvcResult loggedOut = mockMvc.perform(post("/api/v1/auth/logout")
                        .with(remoteIp(clientIp))
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE, second)))
                .andExpect(status().isNoContent())
                .andReturn();

        String cleared = loggedOut.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared).startsWith(COOKIE + "=");
        assertThat(cleared).contains("Path=" + RefreshTokenCookies.COOKIE_PATH);
        assertThat(cleared).contains("HttpOnly");
        assertThat(cleared).containsAnyOf("Max-Age=0", "Max-Age=0;");

        // Session is over: the logged-out cookie no longer rotates.
        refreshWithCookie(second, 401);
    }

    @Test
    void logoutWithoutAnyTokenIsIdempotentAndStillClearsCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(remoteIp(clientIp)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.startsWith(COOKIE + "=")));
    }

    @Test
    void missingCookieAndBodyIsRejectedGenerically() throws Exception {
        MvcResult rejected = mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(remoteIp(clientIp)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString())
                .get("message").asText()).isEqualTo(GENERIC);
    }

    @Test
    void malformedCookieValueIsRejectedGenerically() throws Exception {
        MvcResult rejected = refreshWithCookie("not-a-real-token!!!", 401);
        JsonNode body = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(body.get("message").asText()).isEqualTo(GENERIC);
        assertThat(rejected.getResponse().getContentAsString()).doesNotContain("not-a-real-token");
    }

    @Test
    void expiredCookieTokenIsRejectedGenerically() throws Exception {
        MvcResult loggedIn = login(email());
        String raw = rawTokenOf(loggedIn);

        // NB: RefreshToken.expiresAt is mapped updatable=false (expiry is
        // immutable by design), so expiry is backdated with SQL, not save().
        jdbc.update("UPDATE refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),
                RefreshTokenService.sha256Hex(raw));
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(raw)).orElseThrow()
                .getExpiresAt()).isBefore(Instant.now());

        MvcResult rejected = refreshWithCookie(raw, 401);
        assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString())
                .get("message").asText()).isEqualTo(GENERIC);
    }

    @Test
    void databaseStoresOnlyTheHashNeverTheRawToken() throws Exception {
        MvcResult loggedIn = login(email());
        String raw = rawTokenOf(loggedIn);

        List<RefreshToken> rows = refreshTokens.findAll();
        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .extracting(RefreshToken::getTokenHash)
                .contains(RefreshTokenService.sha256Hex(raw));
        assertThat(rows)
                .extracting(RefreshToken::getTokenHash)
                .doesNotContain(raw);
    }

    @Test
    void legacyBodyTokenStillRotatesForNonBrowserClients() throws Exception {
        MvcResult loggedIn = login(email());
        String raw = rawTokenOf(loggedIn);

        // No cookie: JSON-body fallback (migration path, not the SPA flow).
        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pair = objectMapper.readTree(rotated.getResponse().getContentAsString());
        assertThat(pair.get("accessToken").asText()).isNotBlank();
        assertThat(pair.path("refreshToken").isNull()).isTrue();
        assertThat(rawTokenOf(rotated)).isNotBlank().isNotEqualTo(raw);
    }

    @Test
    void cookieWinsOverBodyWhenBothArePresent() throws Exception {
        MvcResult loggedIn = login(email());
        String cookieRaw = rawTokenOf(loggedIn);

        // Stale/unknown body + valid cookie: the cookie decides (still rotates).
        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(remoteIp(clientIp))
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE, cookieRaw))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(rawTokenOf(rotated)).isNotBlank().isNotEqualTo(cookieRaw);
    }

    @Test
    void allowedOriginGetsCredentialsWhileDisallowedGetsNothing() throws Exception {
        // Allowed origin (test default): echo + credentials.
        mockMvc.perform(get("/api/v1/health")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        // Disallowed origin: fail-closed 403 (Spring rejects invalid CORS
        // requests before the controller) with no origin echo, never *.
        MvcResult rejected = mockMvc.perform(get("/api/v1/health")
                        .header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(rejected.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(rejected.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(rejected.getResponse().getContentAsString()).doesNotContain("evil.example");
    }

    @Test
    void preflightFromAllowedOriginSucceedsWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void concurrentRefreshYieldsExactlyOneReplacement() throws Exception {
        MvcResult loggedIn = login(email());
        String raw = rawTokenOf(loggedIn);

        String sharedIp = "10.2.9.9";
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                gate.await();
                return mockMvc.perform(post("/api/v1/auth/refresh")
                                .with(remoteIp(sharedIp))
                                .cookie(new jakarta.servlet.http.Cookie(COOKIE, raw)))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            gate.countDown();

            List<Integer> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }
}
