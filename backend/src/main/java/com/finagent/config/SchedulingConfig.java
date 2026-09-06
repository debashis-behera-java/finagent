package com.finagent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Task 4: enables Spring scheduling for the refresh-token cleanup job.
 *
 * <p>Uses the framework default single-threaded scheduler — deliberately NO
 * custom thread pool (one hourly bulk DELETE needs no pool; see
 * {@code RefreshTokenCleanupScheduler}). Present only when the auth boundary
 * is enabled, so the DB-less dev profile stays exactly as before.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
