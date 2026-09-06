package com.finagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser access for the Phase 11 React dashboard (minimal CORS surface).
 *
 * <p>Only {@code /api/**} is exposed, only GET/POST, no wildcard origin.
 * Allowed origins come from {@code finagent.app.cors-allowed-origins}
 * (env {@code FINAGENT_CORS_ALLOWED_ORIGINS}, comma-separated). The Vite dev
 * server additionally proxies /api, so this matters most for separately-hosted
 * production builds.</p>
 *
 * <p>Task 3: credentials are enabled because the refresh token now travels in
 * the HttpOnly {@code finagent_rt} cookie — without
 * {@code allowCredentials(true)} browsers would neither send it nor accept the
 * server's {@code Set-Cookie}. Consequences, enforced here and at startup:</p>
 * <ul>
 *   <li>origins stay explicit (never {@code *}): Spring rejects
 *   {@code allowCredentials(true)} combined with a wildcard, and the prod
 *   validator ({@code ProductionSecretValidator}) refuses localhost/wildcard
 *   origins on the {@code prod} profile;</li>
 *   <li>the SPA sends {@code credentials: 'include'} (see
 *   {@code frontend/src/api/client.ts}) so the cookie flows on auth calls.</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FinAgentProperties properties;

    public WebConfig(FinAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.getApp().getCorsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
