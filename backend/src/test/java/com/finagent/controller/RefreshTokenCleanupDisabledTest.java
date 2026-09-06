package com.finagent.controller;

import com.finagent.auth.RefreshTokenCleanupScheduler;
import com.finagent.auth.RefreshTokenCleanupService;
import com.finagent.auth.RefreshTokenService;
import com.finagent.model.Role;
import com.finagent.model.User;
import com.finagent.repository.RefreshTokenRepository;
import com.finagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4: with {@code finagent.auth.refresh-token.cleanup-enabled=false} the
 * scheduled entry point is a documented no-op — dead rows are left alone and
 * no run statistics are recorded. Operators can pause housekeeping without a
 * restart. (Separate context from the enabled suite by property override.)
 */
@SpringBootTest(properties = "finagent.auth.refresh-token.cleanup-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenCleanupDisabledTest {

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

    @BeforeEach
    void clean() {
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void disabledSchedulerLeavesDeadRowsAlone() {
        User user = users.save(new User("paused-" + UUID.randomUUID() + "@example.com",
                new BCryptPasswordEncoder().encode("Secret123"), Role.USER));
        String raw = refreshService.issue(user).refreshToken();
        String hash = RefreshTokenService.sha256Hex(raw);
        jdbc.update("UPDATE refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(8 * 86400L)), hash);

        scheduler.runSafely();

        assertThat(refreshTokens.findByTokenHash(hash)).isPresent();
        assertThat(cleanup.stats().lastCleanupAt()).isNull();
        assertThat(cleanup.stats().lastCleanupDeleted()).isZero();
    }
}
