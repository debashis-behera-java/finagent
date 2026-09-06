package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Task 4: periodic trigger for refresh-token housekeeping.
 *
 * <p>Runs on {@code finagent.auth.refresh-token.cleanup-interval} (fixed
 * delay, default 1h) on the default single-threaded scheduler — no custom
 * pool. The run is fenced twice: the {@code cleanup-enabled} flag is
 * re-checked on every firing (so operators can pause housekeeping without a
 * restart), and every database failure is caught and logged — a failed
 * cleanup never propagates, never retries inline, and never affects serving
 * traffic. Only the deleted-row count is logged; token values and hashes
 * never appear.</p>
 */
@Component
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenCleanupService cleanup;
    private final FinAgentProperties properties;

    public RefreshTokenCleanupScheduler(RefreshTokenCleanupService cleanup,
                                        FinAgentProperties properties) {
        this.cleanup = cleanup;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${finagent.auth.refresh-token.cleanup-interval:PT1H}")
    public void runSafely() {
        if (!properties.getAuth().getRefreshToken().isCleanupEnabled()) {
            log.debug("Refresh-token cleanup skipped (cleanup-enabled=false)");
            return;
        }
        try {
            long deleted = cleanup.runCleanup();
            if (deleted == 0) {
                log.debug("Refresh-token cleanup ran: nothing past retention");
            }
            // Non-zero counts are logged by the service itself (info level).
        } catch (Exception ex) {
            // Containment: a housekeeping failure must never crash the app or
            // kill the scheduler thread — the next run retries on schedule.
            // No token material is logged; the message alone suffices.
            log.error("Refresh-token cleanup failed (next run will retry): {}", ex.getMessage());
        }
    }
}
