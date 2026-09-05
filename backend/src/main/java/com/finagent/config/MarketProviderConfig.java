package com.finagent.config;

import com.finagent.common.HttpRetryExecutor;
import com.finagent.market.MarketDataProvider;
import com.finagent.market.adapter.AlphaVantageMarketAdapter;
import com.finagent.market.adapter.StubMarketDataProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the active {@link MarketDataProvider} implementation from configuration.
 * Swapping providers touches zero business code.
 */
@Configuration
public class MarketProviderConfig {

    @Bean
    public HttpRetryExecutor httpRetryExecutor(FinAgentProperties properties) {
        var market = properties.getMarket();
        return new HttpRetryExecutor(market.getRetryAttempts(), market.getRetryBackoff());
    }

    @Bean
    public MarketDataProvider marketDataProvider(FinAgentProperties properties,
                                                 @Qualifier("httpRetryExecutor") HttpRetryExecutor retryExecutor,
                                                 RestClient.Builder restClientBuilder) {
        var market = properties.getMarket();
        return switch (market.getProvider()) {
            case "alpha-vantage" -> {
                // Transport settings (timeouts) are applied to the builder here, BEFORE the
                // adapter builds its client, so test doubles can bind to the same builder.
                SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
                requestFactory.setConnectTimeout((int) market.getTimeout().toMillis());
                requestFactory.setReadTimeout((int) market.getTimeout().toMillis());
                RestClient.Builder configuredBuilder = restClientBuilder.requestFactory(requestFactory);
                yield new AlphaVantageMarketAdapter(configuredBuilder, market, retryExecutor);
            }
            case "stub" -> new StubMarketDataProvider();
            default -> throw new IllegalStateException(
                    "Unknown market provider '%s' (supported: stub, alpha-vantage)"
                            .formatted(market.getProvider()));
        };
    }
}
