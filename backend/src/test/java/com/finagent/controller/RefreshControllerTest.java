package com.finagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.auth.RefreshTokenCookies;
import com.finagent.repository.RefreshTokenRepository;
import com.finagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 2 + Task 3: refresh/logout HTTP contract on H2 — cookie-session
 * issuance, strict rotation over the wire, reuse detection, logout
 * revocation, generic failures, and cross-user isolation.
 *
 * <p>Task 3: the raw refresh token travels in the HttpOnly
 * {@code finagent_rt} cookie ({@code Set-Cookie} on issue, {@code Cookie} on
 * use) — never in JSON. Helpers below extract the cookie from issuance
 * responses and present it back exactly like a browser would. The legacy
 * JSON-body token is still accepted as a non-browser fallback (covered by
 * {@code AuthRefreshCookieTest}); this class exercises the primary cookie
 * path for every rotation scenario.</p>
 *
 * <p>Rate-limit isolation: each test mints a distinct client IP (own 10.1.x
 * range, separate from AuthControllerTest's 10.0.x range).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshControllerTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
    private static final String GENERIC = "Invalid or expired refresh token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private ObjectMapper objectMapper;

    private String clientIp;

    @BeforeEach
    void freshIdentity() {
        clientIp = "10.1.0." + IP_SEQ.getAndIncrement();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Raw refresh token from the issuance {@code Set-Cookie} header (browser view). */
    private static String extractRefreshCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .as("issuance must set the refresh cookie")
                .isNotNull()
                .startsWith(RefreshTokenCookies.COOKIE_NAME + "=");
        String remainder = setCookie.substring((RefreshTokenCookies.COOKIE_NAME + "=").length());
        String raw = remainder.substring(0, remainder.indexOf(';'));
        assertThat(raw).isNotBlank();
        return raw;
    }

    private static MockHttpServletRequestBuilder withRefreshCookie(
            MockHttpServletRequestBuilder builder, String rawToken) {
        // NB: MockMvc only exposes cookies set via the builder — a raw
        // "Cookie" header is ignored by MockHttpServletRequest.getCookies().
        return builder.cookie(new jakarta.servlet.http.Cookie(RefreshTokenCookies.COOKIE_NAME, rawToken));
    }

    private String email() {
        return "refresh-" + UUID.randomUUID() + "@example.com";
    }

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult refreshViaCookie(String rawToken, int expectedStatus) throws Exception {
        return mockMvc.perform(withRefreshCookie(
                                post("/api/v1/auth/refresh").with(remoteIp(clientIp)), rawToken))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult refreshViaBody(String rawToken, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void logoutViaCookie(String rawToken, int expectedStatus) throws Exception {
        mockMvc.perform(withRefreshCookie(
                                post("/api/v1/auth/logout").with(remoteIp(clientIp)), rawToken))
                .andExpect(status().is(expectedStatus));
    }

    private void logoutViaBody(String rawToken, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void registerAndLoginReturnTokenPair() throws Exception {
        String mail = email();
        MvcResult registered = register(mail);
        JsonNode registeredBody = objectMapper.readTree(registered.getResponse().getContentAsString());
        assertThat(registeredBody.get("accessToken").asText()).isNotBlank();
        assertThat(registeredBody.get("refreshExpiresIn").asLong()).isPositive();
        // Task 3: raw refresh token lives in the cookie, never in JSON.
        assertThat(registeredBody.path("refreshToken").isNull()).isTrue();
        String firstCookie = extractRefreshCookie(registered);
        assertThat(registered.getResponse().getContentAsString()).doesNotContain(firstCookie);

        MvcResult loggedIn = login(mail);
        JsonNode loggedInBody = objectMapper.readTree(loggedIn.getResponse().getContentAsString());
        assertThat(loggedInBody.get("accessToken").asText()).isNotBlank();
        assertThat(loggedInBody.path("refreshToken").isNull()).isTrue();
        String secondCookie = extractRefreshCookie(loggedIn);
        assertThat(secondCookie).isNotBlank().isNotEqualTo(firstCookie);
        assertThat(loggedIn.getResponse().getContentAsString()).doesNotContain(secondCookie);
    }

    @Test
    void refreshRotatesPairAndNewAccessTokenWorks() throws Exception {
        String mail = email();
        register(mail);
        String firstRefresh = extractRefreshCookie(login(mail));

        MvcResult rotated = refreshViaCookie(firstRefresh, 200);
        JsonNode pair = objectMapper.readTree(rotated.getResponse().getContentAsString());
        assertThat(pair.get("accessToken").asText()).isNotBlank();
        assertThat(pair.path("refreshToken").isNull()).isTrue();
        String secondRefresh = extractRefreshCookie(rotated);
        assertThat(secondRefresh).isNotBlank().isNotEqualTo(firstRefresh);
        assertThat(rotated.getResponse().getContentAsString()).doesNotContain(secondRefresh);

        // The rotated access token authenticates.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + pair.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(mail));

        // The consumed refresh token is dead.
        refreshViaCookie(firstRefresh, 401);
    }

    @Test
    void replayingConsumedTokenKillsWholeFamily() throws Exception {
        String mail = email();
        register(mail);
        String first = extractRefreshCookie(login(mail));
        String second = extractRefreshCookie(refreshViaCookie(first, 200));

        refreshViaCookie(first, 401);

        // Even the legitimate replacement died with the family.
        refreshViaCookie(second, 401);
    }

    @Test
    void logoutRevokesSession() throws Exception {
        String mail = email();
        register(mail);
        String first = extractRefreshCookie(login(mail));
        String second = extractRefreshCookie(refreshViaCookie(first, 200));

        logoutViaCookie(second, 204);
        refreshViaCookie(second, 401);
    }

    @Test
    void logoutWithUnknownTokenIsSilent() throws Exception {
        logoutViaBody("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 204);
    }

    @Test
    void refreshFailuresAreGenericAndSafe() throws Exception {
        MvcResult rejected = refreshViaBody("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", 401);
        JsonNode body = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(body.get("message").asText()).isEqualTo(GENERIC);
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(rejected.getResponse().getContentAsString()).doesNotContain("at com.finagent");

        // Blank token fails validation (400), never reaches rotation logic.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attackerCannotDisturbAnotherUsersSession() throws Exception {
        String victimMail = email();
        register(victimMail);
        String victimRefresh = extractRefreshCookie(login(victimMail));

        String attackerMail = email();
        register(attackerMail);
        login(attackerMail);

        refreshViaBody("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", 401);

        // Victim session intact.
        MvcResult rotated = refreshViaCookie(victimRefresh, 200);
        assertThat(extractRefreshCookie(rotated)).isNotBlank();
    }
}
