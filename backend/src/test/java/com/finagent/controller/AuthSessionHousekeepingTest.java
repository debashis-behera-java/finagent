package com.finagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.auth.RefreshTokenCleanupScheduler;
import com.finagent.auth.RefreshTokenCleanupService;
import com.finagent.auth.RefreshTokenService;
import com.finagent.model.RefreshToken;
import com.finagent.model.Role;
import com.finagent.model.User;
import com.finagent.repository.RefreshTokenRepository;
import com.finagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 4: housekeeping + session observability on H2 — retention-gated
 * deletion (expired/revoked), survival of live and tripwire rows, reuse
 * detection intact after cleanup, concurrent-refresh safety, and the
 * ADMIN-only statistics endpoint (auth matrix + no-leak shape).
 *
 * <p>Default retention is 7d: "old" seeds are backdated 8d (past grace),
 * "recent" seeds 1d (inside grace). {@code expires_at} is
 * {@code updatable=false} by design, so backdating uses SQL.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSessionHousekeepingTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private RefreshTokenService refreshService;

    @Autowired
    private RefreshTokenCleanupService cleanup;

    @Autowired
    private RefreshTokenCleanupScheduler scheduler;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String clientIp;

    @BeforeEach
    void freshIdentity() {
        clientIp = "10.3.0." + IP_SEQ.getAndIncrement();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private User createUser(String email) {
        return users.save(new User(email,
                new BCryptPasswordEncoder().encode("Secret123"), Role.USER));
    }

    private void backdateExpires(String hash, Instant at) {
        jdbc.update("UPDATE refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                Timestamp.from(at), hash);
    }

    private void backdateRevoked(String hash, Instant at) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
                Timestamp.from(at), hash);
    }

    @Test
    void expiredBeyondRetentionIsDeletedWhileRecentExpirySurvives() {
        User user = createUser("house-" + UUID.randomUUID() + "@example.com");
        String oldRaw = refreshService.issue(user).refreshToken();
        String recentRaw = refreshService.issue(user).refreshToken();
        backdateExpires(RefreshTokenService.sha256Hex(oldRaw), Instant.now().minusSeconds(8 * 86400L));
        backdateExpires(RefreshTokenService.sha256Hex(recentRaw), Instant.now().minusSeconds(86400L));

        long deleted = cleanup.runCleanup();

        assertThat(deleted).isEqualTo(1);
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(oldRaw))).isEmpty();
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(recentRaw))).isPresent();
    }

    @Test
    void revokedBeyondRetentionIsDeletedWhileFreshLogoutSurvives() {
        User user = createUser("revoke-" + UUID.randomUUID() + "@example.com");
        String oldRaw = refreshService.issue(user).refreshToken();
        String freshRaw = refreshService.issue(user).refreshToken();
        refreshService.logout(oldRaw);
        refreshService.logout(freshRaw);
        // Age only the revocation marker; expiry stays in the future.
        backdateRevoked(RefreshTokenService.sha256Hex(oldRaw), Instant.now().minusSeconds(8 * 86400L));

        long deleted = cleanup.runCleanup();

        assertThat(deleted).isEqualTo(1);
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(oldRaw))).isEmpty();
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(freshRaw))).isPresent();
    }

    @Test
    void liveRowsSurviveAndReuseDetectionStillWorksAfterCleanup() {
        User user = createUser("reuse-" + UUID.randomUUID() + "@example.com");
        String first = refreshService.issue(user).refreshToken();
        String second = refreshService.rotate(first).refreshToken();

        // Used-but-unexpired tripwire + live successor: nothing deletable.
        assertThat(cleanup.runCleanup()).isZero();
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(first))).isPresent();
        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(second))).isPresent();

        // Replay still kills the whole family (reuse semantics intact).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refreshService.rotate(first))
                .isInstanceOf(com.finagent.auth.InvalidTokenException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refreshService.rotate(second))
                .isInstanceOf(com.finagent.auth.InvalidTokenException.class);
    }

    @Test
    void activeTokenSurvivesCleanupAndStillRotates() {
        User user = createUser("live-" + UUID.randomUUID() + "@example.com");
        String raw = refreshService.issue(user).refreshToken();

        assertThat(cleanup.runCleanup()).isZero();

        RefreshTokenService.Rotation rotation = refreshService.rotate(raw);
        assertThat(rotation.refreshToken()).isNotBlank().isNotEqualTo(raw);
    }

    @Test
    void concurrentRefreshRemainsSafeAroundCleanup() throws Exception {
        User user = createUser("race-" + UUID.randomUUID() + "@example.com");
        String raw = refreshService.issue(user).refreshToken();
        cleanup.runCleanup();

        String sharedIp = "10.3.9.9";
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                gate.await();
                return mockMvc.perform(post("/api/v1/auth/refresh")
                                .with(remoteIp(sharedIp))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + raw + "\"}"))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            gate.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void schedulerRunIsWiredToCleanup() {
        User user = createUser("sched-" + UUID.randomUUID() + "@example.com");
        String raw = refreshService.issue(user).refreshToken();
        backdateExpires(RefreshTokenService.sha256Hex(raw), Instant.now().minusSeconds(8 * 86400L));

        // Enabled by default: the scheduled entry point deletes through the service.
        scheduler.runSafely();

        assertThat(refreshTokens.findByTokenHash(RefreshTokenService.sha256Hex(raw))).isEmpty();
        assertThat(cleanup.stats().lastCleanupAt()).isNotNull();
        assertThat(cleanup.stats().lastCleanupDeleted()).isEqualTo(1);
    }

    @Test
    void anonymousStatsRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/auth/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userIsForbiddenFromStats() throws Exception {
        mockMvc.perform(get("/api/v1/admin/auth/sessions"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminGetsAggregateStatsWithoutLeaks() throws Exception {
        User active = createUser("stat-a-" + UUID.randomUUID() + "@example.com");
        User gone = createUser("stat-b-" + UUID.randomUUID() + "@example.com");
        String activeRaw = refreshService.issue(active).refreshToken();
        String revokedRaw = refreshService.issue(gone).refreshToken();
        refreshService.logout(revokedRaw);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/auth/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.revoked").value(1))
                .andExpect(jsonPath("$.expired").value(0))
                .andExpect(jsonPath("$.families").value(2))
                .andExpect(jsonPath("$.lastCleanupDeleted").value(0))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.has("active") && json.has("revoked") && json.has("expired")
                && json.has("families") && json.has("lastCleanupDeleted")).isTrue();
        // No token material anywhere: raw values, stored hashes, secrets.
        List<String> secrets = new ArrayList<>();
        secrets.add(activeRaw);
        secrets.add(revokedRaw);
        refreshTokens.findAll().stream().map(RefreshToken::getTokenHash).forEach(secrets::add);
        for (String secret : secrets) {
            assertThat(body).doesNotContain(secret);
        }
        assertThat(body.toLowerCase()).doesNotContain("jwt")
                .doesNotContain("secret").doesNotContain("password");
    }

    @Test
    void realUserTokenIsForbiddenOnStatsEndpoint() throws Exception {
        String email = "plain-" + UUID.randomUUID() + "@example.com";
        MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                register.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/v1/admin/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
