package com.finagent.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: limiter budgets, group/key isolation, window reset, and the
 * bucket-count bound. Deterministic — no Spring context, tiny windows.
 */
class RateLimitServiceTest {

    @Test
    void allowsUpToBudgetThenDenies() {
        RateLimitService limiter = new RateLimitService(3, Duration.ofMinutes(1), 100, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isFalse();
    }

    @Test
    void budgetsAreIsolatedByKeyAndGroup() {
        RateLimitService limiter = new RateLimitService(1, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("1.1.1.1", RateLimitService.Group.AUTH)).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", RateLimitService.Group.AUTH)).isFalse();
        // Different client unaffected.
        assertThat(limiter.tryAcquire("2.2.2.2", RateLimitService.Group.AUTH)).isTrue();
        // Different group unaffected.
        assertThat(limiter.tryAcquire("1.1.1.1", RateLimitService.Group.RESEARCH)).isTrue();
    }

    @Test
    void windowExpiryResetsBudget() throws Exception {
        RateLimitService limiter = new RateLimitService(1, Duration.ofMillis(20), 1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isFalse();
        Thread.sleep(60);
        assertThat(limiter.tryAcquire("1.2.3.4", RateLimitService.Group.AUTH)).isTrue();
    }

    @Test
    void bucketCountStaysBounded() {
        RateLimitService limiter = new RateLimitService(1, Duration.ofMinutes(10), 1, Duration.ofMinutes(10));

        for (int i = 0; i < RateLimitService.MAX_BUCKETS + 50; i++) {
            limiter.tryAcquire("10.0.0." + i, RateLimitService.Group.AUTH);
        }
        assertThat(limiter.bucketCount()).isLessThanOrEqualTo(RateLimitService.MAX_BUCKETS);
    }
}
