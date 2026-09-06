package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import com.finagent.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task 4: refresh-token housekeeping — scheduled deletion of dead rows plus
 * minimal session observability. Authentication semantics are untouched: this
 * service never issues, rotates, or revokes tokens.
 *
 * <h2>Cleanup policy (why it cannot break reuse detection)</h2>
 * <p>A row is deleted only when it is dead <i>and</i> past the retention
 * grace ({@code finagent.auth.refresh-token.retention}, default 7d):</p>
 * <ul>
 *   <li>{@code expires_at <= now - retention}: {@code RefreshTokenService.rotate()}
 *   rejects such rows as <i>expired</i> before ever reading
 *   {@code used_at}/{@code revoked_at}. Whether the row exists (expired-401)
 *   or not (unknown-401), the answer is the same generic 401 with no state
 *   change — the replay tripwire is already spent.</li>
 *   <li>{@code revoked_at <= now - retention}: presenting a revoked row
 *   changes no state (the family is already dead), so deleting it is
 *   observationally identical — generic 401 either way.</li>
 * </ul>
 * <p>Used-but-unexpired rows are <b>never</b> deleted: they are the live
 * replay tripwires — presenting one revokes its whole family (theft
 * signal). The currently usable token of every family always survives:
 * it is active, hence neither expired nor revoked.</p>
 *
 * <h2>Operational properties</h2>
 * <ul>
 *   <li>One bulk {@code DELETE} statement per run — no token entity (and no
 *   hash) is ever loaded into memory; only the deleted-row count is
 *   returned. The table grows at most ~2 rows per login/rotation, so a
 *   single hourly statement is bounded in practice; no batching layer.</li>
 *   <li>Single transaction per run; concurrent rotations only ever touch
 *   live (unexpired, unrevoked) rows, which the predicate cannot match —
 *   the job and rotation are disjoint by construction.</li>
 *   <li>Logs the deleted count (never token values, never hashes); database
 *   failures propagate to the scheduler, which contains them (see
 *   {@code RefreshTokenCleanupScheduler}).</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository tokens;
    private final FinAgentProperties properties;

    /** Last run timestamp (null before the first run). Read by HTTP threads. */
    private final AtomicReference<Instant> lastCleanupAt = new AtomicReference<>();
    /** Rows removed by the last run. Read by HTTP threads. */
    private final AtomicLong lastCleanupDeleted = new AtomicLong();

    public RefreshTokenCleanupService(RefreshTokenRepository tokens, FinAgentProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    /**
     * Delete dead rows past the retention grace. Returns the removed count
     * and records run statistics for the admin statistics endpoint.
     */
    @Transactional
    public long runCleanup() {
        long retentionMillis = properties.getAuth().getRefreshToken().getRetention().toMillis();
        Instant now = Instant.now();
        Instant cutoff = now.minusMillis(retentionMillis);
        int deleted = tokens.deleteCleanupCandidates(cutoff, cutoff);
        lastCleanupAt.set(now);
        lastCleanupDeleted.set(deleted);
        log.info("Refresh-token cleanup removed {} row(s) past retention", deleted);
        return deleted;
    }

    /** Aggregate session counters — no token material, only counts. */
    @Transactional(readOnly = true)
    public SessionStats stats() {
        Instant now = Instant.now();
        return new SessionStats(
                tokens.countActive(now),
                tokens.countRevoked(),
                tokens.countExpired(now),
                tokens.countFamilies(),
                lastCleanupAt.get(),
                lastCleanupDeleted.get());
    }

    /** Aggregate session view. Counters overlap by design (see repository). */
    public record SessionStats(long active, long revoked, long expired, long families,
                               Instant lastCleanupAt, long lastCleanupDeleted) {
    }
}
