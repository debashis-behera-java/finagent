package com.finagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2: refresh-token expiration is configuration-driven (never hardcoded in
 * Java) — the default applies out of the box and the environment-style
 * property ({@code FINAGENT_AUTH_REFRESH_TTL} → relaxed binding) overrides it,
 * including in production.
 */
class RefreshTokenTtlConfigTest {

    @Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(FinAgentProperties.class)
    static class Binding {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(Binding.class));

    @Test
    void defaultRefreshTtlIsFourteenDays() {
        assertThat(new FinAgentProperties().getAuth().getRefreshTokenTtl())
                .isEqualTo(Duration.ofDays(14));
    }

    @Test
    void refreshTtlBindsFromProperty() {
        runner.withPropertyValues("finagent.auth.refresh-token-ttl=30d")
                .run(ctx -> assertThat(ctx.getBean(FinAgentProperties.class)
                        .getAuth().getRefreshTokenTtl())
                        .isEqualTo(Duration.ofDays(30)));
    }

    @Test
    void accessTtlIsUnaffectedByRefreshTtl() {
        runner.withPropertyValues("finagent.auth.refresh-token-ttl=30d")
                .run(ctx -> assertThat(ctx.getBean(FinAgentProperties.class)
                        .getAuth().getTokenTtl())
                        .isEqualTo(Duration.ofHours(1)));
    }
}
