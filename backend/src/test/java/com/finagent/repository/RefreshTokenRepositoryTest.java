package com.finagent.repository;

import com.finagent.model.RefreshToken;
import com.finagent.model.Role;
import com.finagent.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2: refresh-token persistence on H2 — save/lookup by hash, uniqueness,
 * family queries, user cascade. (Flyway V5 itself is exercised against real
 * PostgreSQL in CI/Testcontainers; Hibernate validates the mapping here.)
 */
@DataJpaTest
@ActiveProfiles("test")
class RefreshTokenRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private UserRepository users;

    private User user(String email) {
        User user = new User(email, "$2a$10$testhashfortests00000000000000000000001", Role.USER);
        return users.saveAndFlush(user);
    }

    private static String hash(String seed) {
        // 64-hex-char stand-in (service hashes with SHA-256; the column only cares about shape).
        return com.finagent.auth.RefreshTokenService.sha256Hex(seed);
    }

    private RefreshToken token(User user, String seed, UUID family) {
        return new RefreshToken(user, hash(seed), family,
                Instant.now().plus(14, ChronoUnit.DAYS));
    }

    @Test
    void saveAndFindByHash() {
        User user = user("holder@example.com");
        UUID family = UUID.randomUUID();
        tokens.saveAndFlush(token(user, "raw-token-a", family));
        em.clear();

        RefreshToken found = tokens.findByTokenHash(hash("raw-token-a")).orElseThrow();
        assertThat(found.getFamilyId()).isEqualTo(family);
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getUsedAt()).isNull();
        assertThat(found.getRevokedAt()).isNull();
        assertThat(found.isActive(Instant.now())).isTrue();
    }

    @Test
    void duplicateHashIsRejected() {
        User user = user("dupe@example.com");
        tokens.saveAndFlush(token(user, "same-raw", UUID.randomUUID()));

        assertThatThrownBy(() -> tokens.saveAndFlush(token(user, "same-raw", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByFamilyIdReturnsWholeChain() {
        User user = user("family@example.com");
        UUID family = UUID.randomUUID();
        tokens.saveAndFlush(token(user, "chain-1", family));
        tokens.saveAndFlush(token(user, "chain-2", family));
        tokens.saveAndFlush(token(user, "other-family", UUID.randomUUID()));
        em.clear();

        List<RefreshToken> chain = tokens.findByFamilyId(family);
        assertThat(chain).hasSize(2);
    }

    @Test
    void deletingUserCascadesToTokens() {
        User user = user("gone@example.com");
        UUID userId = user.getId();
        tokens.saveAndFlush(token(user, "orphan-candidate", UUID.randomUUID()));
        em.clear();

        users.deleteById(userId);
        em.flush();

        assertThat(tokens.findByUserId(userId)).isEmpty();
    }

}
