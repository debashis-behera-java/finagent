package com.finagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 documentation configuration (springdoc).
 * Swagger UI is served at /swagger-ui.html.
 *
 * <p>Phase 16: gated by {@code finagent.swagger.enabled} (open in dev/test,
 * closed in production via application-prod.yml + the security chain's deny
 * rule as a second layer).</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "finagent.swagger", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    @Bean
    public OpenAPI finAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FINAGENT API")
                        .description("AI-powered personal finance & investment research platform. "
                                + "FINAGENT is a research tool, NOT a financial advisor.")
                        .version("1.0.0"));
    }
}
