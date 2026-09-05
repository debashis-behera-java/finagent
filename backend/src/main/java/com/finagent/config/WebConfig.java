package com.finagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser access for the Phase 11 React dashboard (minimal CORS surface).
 *
 * <p>Only {@code /api/**} is exposed, only GET/POST, no credentials, no wildcard
 * origin. Allowed origins come from {@code finagent.app.cors-allowed-origins}
 * (env {@code FINAGENT_CORS_ALLOWED_ORIGINS}, comma-separated). The Vite dev
 * server additionally proxies /api, so this matters most for separately-hosted
 * production builds.</p>
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
                .maxAge(3600);
    }
}
