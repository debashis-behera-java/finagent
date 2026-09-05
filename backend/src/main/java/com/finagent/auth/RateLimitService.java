package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 16: in-memory fixed-window rate limiter (per client key + group).
 *
 * <p>Bounded: at most {@value #MAX_BUCKETS} buckets are retained; the least
 * recently seen bucket is evicted on overflow, and expired windows are reset
 * in place (no background threads, no leak). Deterministic: pure function of
 * (key, group, wall clock) — tests construct it directly with tiny limits.</p>
 *
 * <p><b>Single-instance only.</b> Each application node keeps its own counters,
 * so this does NOT enforce global limits in a multi-instance deployment
 * (documented in docs/security.md; a shared store like Redis would be needed).</p>
 */
@Component
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RateLimitService {

    /** Hard cap on tracked buckets — prevents unbounded map growth. */
    static final int MAX_BUCKETS = 10_000;

    /** Endpoint groups with independent budgets. */
    public enum Group {
        AUTH,
        RESEARCH
    }

    private final int authMaxAttempts;
    private final long authWindowMillis;
    private final int researchMaxAttempts;
    private final long researchWindowMillis;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitService(FinAgentProperties properties) {
        this(properties.getRateLimit().getAuthMaxAttempts(),
                properties.getRateLimit().getAuthWindow(),
                properties.getRateLimit().getResearchMaxAttempts(),
                properties.getRateLimit().getResearchWindow());
    }

    /** Direct constructor for deterministic unit tests. */
    public RateLimitService(int authMaxAttempts, Duration authWindow,
                            int researchMaxAttempts, Duration researchWindow) {
        this.authMaxAttempts = Math.max(1, authMaxAttempts);
        this.authWindowMillis = Math.max(1, authWindow.toMillis());
        this.researchMaxAttempts = Math.max(1, researchMaxAttempts);
        this.researchWindowMillis = Math.max(1, researchWindow.toMillis());
    }

    /**
     * Attempt one event. Returns {@code true} when allowed, {@code false} when
     * the caller exceeded its budget (caller maps to HTTP 429).
     */
    public boolean tryAcquire(String clientKey, Group group) {
        int max = group == Group.AUTH ? authMaxAttempts : researchMaxAttempts;
        long window = group == Group.AUTH ? authWindowMillis : researchWindowMillis;
        String bucketKey = group.name() + "|" + clientKey;
        long now = System.currentTimeMillis();

        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> new Bucket(now));
        try {
            synchronized (bucket) {
                if (now - bucket.windowStart >= window) {
                    bucket.windowStart = now;
                    bucket.count = 0;
                }
                if (bucket.count >= max) {
                    return false;
                }
                bucket.count++;
                return true;
            }
        } finally {
            evictIfOverCapacity();
        }
    }

    /** Visible for tests: current number of tracked buckets. */
    int bucketCount() {
        evictIfOverCapacity();
        return buckets.size();
    }

    private void evictIfOverCapacity() {
        if (buckets.size() <= MAX_BUCKETS) {
            return;
        }
        // Evict the stalest bucket (single pass, bounded work per call).
        String stalest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            long start = entry.getValue().windowStart;
            if (start < oldest) {
                oldest = start;
                stalest = entry.getKey();
            }
        }
        if (stalest != null) {
            buckets.remove(stalest);
        }
    }

    private static final class Bucket {
        long windowStart;
        int count;

        Bucket(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
