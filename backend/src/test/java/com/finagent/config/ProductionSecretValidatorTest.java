package com.finagent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: production secret validation logic — defaults/placeholders fail,
 * real values pass, stub providers skip key checks. Pure unit test.
 */
class ProductionSecretValidatorTest {

    private static final String GOOD_JWT = "prod-secret-that-is-long-enough-and-random-0123456789abcdef";
    private static final String GOOD_KEY = "real-key-from-environment";

    @Test
    void devDefaultsAreRejected() {
        List<String> problems = ProductionSecretValidator.validateValues(
                "finagent-dev-only-jwt-secret-not-for-production-use-0123456789",
                "test-dummy-key-not-real", "finagent-dev-only",
                "stub", "", "stub", "");

        assertThat(problems).hasSize(3);
        assertThat(String.join(" ", problems)).contains("JWT", "OpenAI", "Database");
    }

    @Test
    void blankValuesAreRejected() {
        List<String> problems = ProductionSecretValidator.validateValues(
                "", "", "", "stub", "", "stub", "");

        assertThat(problems).hasSize(3);
    }

    @Test
    void shortJwtSecretIsRejected() {
        List<String> problems = ProductionSecretValidator.validateValues(
                "short-but-not-default", GOOD_KEY, "s3cure-db-password!",
                "stub", "", "stub", "");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("JWT");
    }

    @Test
    void secretValuesNeverAppearInMessages() {
        List<String> problems = ProductionSecretValidator.validateValues(
                "short", "some-key", "some-password", "alpha-vantage", "", "newsapi", "");

        // 3 problems (JWT too short; both real providers keyless) naming only
        // variable names, never secret material.
        assertThat(problems).hasSize(3);
        String joined = String.join(" ", problems);
        assertThat(joined).doesNotContain("short", "some-key", "some-password");
        assertThat(joined).contains("FINAGENT_AUTH_JWT_SECRET", "ALPHA_VANTAGE_API_KEY", "NEWS_API_KEY");
    }

    @Test
    void realConfigurationPasses() {
        List<String> problems = ProductionSecretValidator.validateValues(
                GOOD_JWT, GOOD_KEY, "s3cure-db-password!",
                "alpha-vantage", GOOD_KEY, "newsapi", GOOD_KEY);

        assertThat(problems).isEmpty();
    }

    @Test
    void stubProvidersSkipExternalKeyChecks() {
        List<String> problems = ProductionSecretValidator.validateValues(
                GOOD_JWT, GOOD_KEY, "s3cure-db-password!",
                "stub", "", "stub", "");

        assertThat(problems).isEmpty();
    }

    @Test
    void realProviderWithoutKeyFails() {
        List<String> problems = ProductionSecretValidator.validateValues(
                GOOD_JWT, GOOD_KEY, "s3cure-db-password!",
                "alpha-vantage", "", "stub", "");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("ALPHA_VANTAGE_API_KEY");
    }
}
