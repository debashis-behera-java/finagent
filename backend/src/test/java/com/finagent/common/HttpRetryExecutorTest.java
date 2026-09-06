package com.finagent.common;

import com.finagent.exception.ProviderException;
import com.finagent.exception.ProviderTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRetryExecutorTest {

    @Test
    void retriesTransientFailuresAndSucceeds() {
        HttpRetryExecutor executor = new HttpRetryExecutor(3, Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new ResourceAccessException("transient I/O error");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void timeoutRootCauseBecomesProviderTimeoutException() {
        HttpRetryExecutor executor = new HttpRetryExecutor(2, Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("I/O error on GET request",
                    new SocketTimeoutException("Read timed out"));
        }))
                .isInstanceOf(ProviderTimeoutException.class)
                .isInstanceOf(ProviderException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nonTimeoutIoFailureBecomesProviderException() {
        HttpRetryExecutor executor = new HttpRetryExecutor(2, Duration.ofMillis(1));

        assertThatThrownBy(() -> executor.execute(() -> {
            throw new ResourceAccessException("connection refused");
        }))
                .isInstanceOf(ProviderException.class)
                .isNotInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void providerExceptionIsNotRetried() {
        HttpRetryExecutor executor = new HttpRetryExecutor(3, Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();
        ProviderException original = new ProviderException("rate limited");

        assertThatThrownBy(() -> executor.execute(() -> {
            calls.incrementAndGet();
            throw original;
        })).isSameAs(original);
        assertThat(calls.get()).isEqualTo(1);
    }
}
