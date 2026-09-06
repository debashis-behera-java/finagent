package com.finagent.common;

import com.finagent.exception.ProviderException;
import com.finagent.exception.ProviderTimeoutException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Shared retry strategy for transient upstream failures (I/O errors, read timeouts),
 * used by all provider adapters (market data, news, ...).
 * Non-transient {@link ProviderException}s are NOT retried.
 * After exhausting attempts, a timeout root cause maps to {@link ProviderTimeoutException},
 * everything else to {@link ProviderException}.
 */
public class HttpRetryExecutor {

    private final int maxAttempts;
    private final Duration backoff;

    public HttpRetryExecutor(int maxAttempts, Duration backoff) {
        this.maxAttempts = Math.max(1, maxAttempts);
        // A negative backoff would make Thread.sleep throw IllegalArgumentException
        // at runtime; clamp defensively since these values come from configuration.
        this.backoff = (backoff == null || backoff.isNegative()) ? Duration.ZERO : backoff;
    }

    public <T> T execute(Supplier<T> action) {
        ResourceAccessException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException ex) {
                lastFailure = ex;
                if (attempt < maxAttempts) {
                    sleepQuietly(backoff.multipliedBy(attempt));
                }
            }
        }
        if (rootCauseIsTimeout(lastFailure)) {
            throw new ProviderTimeoutException(
                    "Upstream provider did not respond within timeout after " + maxAttempts + " attempt(s)",
                    lastFailure);
        }
        throw new ProviderException(
                "Upstream provider unreachable after " + maxAttempts + " attempt(s)", lastFailure);
    }

    private boolean rootCauseIsTimeout(Throwable failure) {
        Throwable t = failure;
        while (t != null) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
