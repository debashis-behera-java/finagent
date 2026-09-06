package com.finagent.config;

import com.finagent.market.BenchmarkDataProvider;
import com.finagent.market.adapter.StubBenchmarkDataProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link BenchmarkDataProvider} implementation from
 * configuration ({@code finagent.risk.benchmark-provider}). Currently only the
 * deterministic stub ships; a real adapter can be added here without touching
 * business code.
 */
@Configuration
public class BenchmarkProviderConfig {

    @Bean
    public BenchmarkDataProvider benchmarkDataProvider(FinAgentProperties properties) {
        return switch (properties.getRisk().getBenchmarkProvider()) {
            case "stub" -> new StubBenchmarkDataProvider();
            default -> throw new IllegalStateException(
                    "Unknown benchmark provider '%s' (supported: stub)"
                            .formatted(properties.getRisk().getBenchmarkProvider()));
        };
    }
}