package com.finagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FINAGENT - AI-powered personal finance & investment research platform.
 *
 * <p>Phase 1: project skeleton &amp; foundations. The application boots with the {@code dev}
 * profile without PostgreSQL (JPA auto-configuration is deferred until Phase 2 - see
 * application-dev.yml).</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FinAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinAgentApplication.class, args);
    }
}
