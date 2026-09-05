package com.finagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 16: production fail-fast secret validation. Active ONLY on the
 * {@code prod} profile — dev/test keep their safe dummy values.
 *
 * <p>Production MUST NOT start with the dev JWT secret, the dummy OpenAI key,
 * placeholder provider keys, or a default database password. Every failure
 * names the variable, NEVER its value.</p>
 */
@Component
@Profile("prod")
@Slf4j
public class ProductionSecretValidator implements CommandLineRunner {

    private static final String DEV_JWT_SECRET = "finagent-dev-only-jwt-secret-not-for-production-use-0123456789";
    private static final String DUMMY_OPENAI_KEY = "test-dummy-key-not-real";

    private final Environment env;
    private final FinAgentProperties properties;

    public ProductionSecretValidator(Environment env, FinAgentProperties properties) {
        this.env = env;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        List<String> problems = validate();
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                log.error("PRODUCTION SECRET CHECK FAILED: {}", problem);
            }
            throw new IllegalStateException(
                    "Refusing to start on the prod profile with insecure configuration: "
                            + String.join("; ", problems));
        }
        log.info("Production secret checks passed");
    }

    /** Pure validation logic — unit-tested without a Spring context. */
    static List<String> validateValues(String jwtSecret, String openAiKey,
                                       String dbPassword, String marketProvider, String marketKey,
                                       String newsProvider, String newsKey) {
        List<String> problems = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.isBlank()
                || DEV_JWT_SECRET.equals(jwtSecret)
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || jwtSecret.toLowerCase(java.util.Locale.ROOT).contains("dev-only")
                || jwtSecret.toLowerCase(java.util.Locale.ROOT).contains("change-me")
                || jwtSecret.toLowerCase(java.util.Locale.ROOT).contains("test")) {
            problems.add("JWT signing secret must be configured for production "
                    + "(FINAGENT_AUTH_JWT_SECRET, min 32 bytes, non-default)");
        }
        if (openAiKey == null || openAiKey.isBlank() || DUMMY_OPENAI_KEY.equals(openAiKey)) {
            problems.add("OpenAI API key must be configured for production (SPRING_AI_OPENAI_API_KEY)");
        }
        if (dbPassword == null || dbPassword.isBlank()
                || List.of("finagent", "finagent-dev-only", "change-me", "password", "postgres")
                        .contains(dbPassword.trim().toLowerCase(java.util.Locale.ROOT))) {
            problems.add("Database password must be configured for production "
                    + "(SPRING_DATASOURCE_PASSWORD, non-default)");
        }
        if (!"stub".equals(marketProvider) && (marketKey == null || marketKey.isBlank())) {
            problems.add("Market provider '" + marketProvider + "' requires ALPHA_VANTAGE_API_KEY");
        }
        if (!"stub".equals(newsProvider) && (newsKey == null || newsKey.isBlank())) {
            problems.add("News provider '" + newsProvider + "' requires NEWS_API_KEY");
        }
        return problems;
    }

    private List<String> validate() {
        return validateValues(
                env.getProperty("FINAGENT_AUTH_JWT_SECRET", properties.getAuth().getJwtSecret()),
                env.getProperty("SPRING_AI_OPENAI_API_KEY", ""),
                env.getProperty("SPRING_DATASOURCE_PASSWORD", ""),
                properties.getMarket().getProvider(), properties.getMarket().getApiKey(),
                properties.getNews().getProvider(), properties.getNews().getApiKey());
    }
}
