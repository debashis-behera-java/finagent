package com.finagent.auth;

import com.finagent.model.RefreshToken;
import com.finagent.model.Role;
import com.finagent.model.User;
import com.finagent.repository.RefreshTokenRepository;
import com.finagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2: refresh-token rotation semantics on H2 — issuance, single-use
 * rotation, expiry/revocation/malformed handling, hash-only storage, logout,
 * cross-user isolation, and concurrent-rotation safety.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenServiceTest {

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private UserRepository users;

    private User user;

    @BeforeEach
    void freshUser() {
        tokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(new User("holder-" + UUID.randomUUID() + "@example.com",
                "$2a$10$testhashfortests00000000000000000000001", Role.USER));
    }

    @Test
    void issuedTokenHasEntropyAndStoresOnlyHash() {
        RefreshTokenService.IssuedToken issued = service.issue(user);

        assertThat(issued.refreshToken()).hasSize(43); // 32 bytes, Base64URL, no padding
        assertThat(issued.refreshExpiresIn()).isPositive();

        List<RefreshToken> rows = tokens.findByUserId(user.getId());
        assertThat(rows).hasSize(1);
        RefreshToken row = rows.get(0);
        assertThat(row.getTokenHash())
                .isEqualTo(RefreshTokenService.sha256Hex(issued.refreshToken()))
                .hasSize(64)
                .doesNotContain(issued.refreshToken());
        assertThat(row.isActive(Instant.now())).isTrue();

        // Second issuance is unique (CSPRNG, no collisions in practice).
        assertThat(service.issue(user).refreshToken()).isNotEqualTo(issued.refreshToken());
    }

    @Test
    void rotateSucceedsOnceThenOldTokenDies() {
        String first = service.issue(user).refreshToken();

        RefreshTokenService.Rotation rotation = service.rotate(first);

        assertThat(rotation.user().getId()).isEqualTo(user.getId());
        assertThat(rotation.refreshToken()).isNotBlank().isNotEqualTo(first);

        List<RefreshToken> rows = tokens.findByUserId(user.getId());
        assertThat(rows).hasSize(2);
        RefreshToken consumed = rows.stream()
                .filter(t -> t.getTokenHash().equals(RefreshTokenService.sha256Hex(first)))
                .findFirst().orElseThrow();
        assertThat(consumed.getUsedAt()).isNotNull();
        assertThat(consumed.getReplacedByHash())
                .isEqualTo(RefreshTokenService.sha256Hex(rotation.refreshToken()));
        RefreshToken successor = rows.stream()
                .filter(t -> t.getTokenHash().equals(RefreshTokenService.sha256Hex(rotation.refreshToken())))
                .findFirst().orElseThrow();
        assertThat(successor.getFamilyId()).isEqualTo(consumed.getFamilyId());
        assertThat(successor.isActive(Instant.now())).isTrue();
    }

    @Test
    void replayingConsumedTokenIsReuseAndKillsFamily() {
        String first = service.issue(user).refreshToken();
        String second = service.rotate(first).refreshToken();

        assertThatThrownBy(() -> service.rotate(first))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);

        // The legitimate replacement died with the family: nothing usable remains.
        assertThat(tokens.findByUserId(user.getId()))
                .allMatch(t -> !t.isActive(Instant.now()));
        assertThatThrownBy(() -> service.rotate(second))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);
    }

    @Test
    void expiredTokenIsRejected() {
        // expires_at is immutable after insert (updatable=false, like the audit
        // trail) — so an expired row is built directly, not mutated.
        String raw = "expired-token-seed-" + UUID.randomUUID();
        RefreshToken row = new RefreshToken(user, RefreshTokenService.sha256Hex(raw),
                UUID.randomUUID(), Instant.now().minusSeconds(60));
        tokens.saveAndFlush(row);

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);
    }

    @Test
    void malformedAndUnknownTokensShareOneGenericMessage() {
        assertThatThrownBy(() -> service.rotate(null))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);
        assertThatThrownBy(() -> service.rotate("   "))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);
        assertThatThrownBy(() -> service.rotate("not-a-real-token********************************"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);
        assertThatThrownBy(() -> service.rotate("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);
    }

    @Test
    void logoutRevokesFamilyAndIsIdempotent() {
        String first = service.issue(user).refreshToken();
        String second = service.rotate(first).refreshToken();

        service.logout(second);

        assertThat(tokens.findByUserId(user.getId()))
                .allMatch(t -> !t.isActive(Instant.now()));
        assertThatThrownBy(() -> service.rotate(second))
                .isInstanceOf(InvalidTokenException.class);

        // Unknown / blank logout never fails and never leaks validity.
        service.logout("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        service.logout("  ");
        service.logout(null);
    }

    @Test
    void attackerWithoutTokenCannotDisturbVictimSession() {
        String victimRaw = service.issue(user).refreshToken();

        assertThatThrownBy(() -> service.rotate("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(RefreshTokenService.GENERIC_FAILURE);

        // Victim session untouched: legitimate rotation still works.
        RefreshTokenService.Rotation rotation = service.rotate(victimRaw);
        assertThat(rotation.user().getId()).isEqualTo(user.getId());
    }

    @Test
    void concurrentRotationYieldsAtMostOneValidReplacement() throws Exception {
        String raw = service.issue(user).refreshToken();
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger authFailures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) {
                            return null;
                        }
                        service.rotate(raw);
                        successes.incrementAndGet();
                    } catch (InvalidTokenException expected) {
                        authFailures.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Exactly one winner; the loser took the reuse path (strict family
        // revocation), so no two DISTINCT valid replacements can ever exist.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(authFailures.get()).isEqualTo(1);
        List<RefreshToken> rows = tokens.findByUserId(user.getId());
        assertThat(rows).hasSize(2); // original + the single replacement
        assertThat(rows.stream().filter(t -> t.isActive(Instant.now())).count()).isLessThanOrEqualTo(1);
        assertThat(rows.stream().map(RefreshToken::getTokenHash).distinct().count()).isEqualTo(2);
    }

    @Test
    void rawTokenNeverAppearsInStorage() {
        List<String> raws = new ArrayList<>();
        raws.add(service.issue(user).refreshToken());
        raws.add(service.rotate(raws.get(0)).refreshToken());
        service.issue(user);

        List<String> stored = tokens.findAll().stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getTokenHash(),
                        String.valueOf(t.getReplacedByHash())))
                .toList();
        for (String raw : raws) {
            assertThat(stored).doesNotContain(raw);
        }
        assertThat(Collections.frequency(
                tokens.findAll().stream().map(RefreshToken::getTokenHash).toList(),
                RefreshTokenService.sha256Hex(raws.get(0)))).isEqualTo(1);
    }
}
