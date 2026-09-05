package com.finagent.config;

import com.finagent.common.HttpRetryExecutor;
import com.finagent.news.NewsDataProvider;
import com.finagent.news.adapter.NewsApiAdapter;
import com.finagent.news.adapter.StubNewsDataProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the active {@link NewsDataProvider} implementation from configuration.
 * Swapping providers touches zero business code.
 */
@Configuration
public class NewsProviderConfig {

    /**
     * Retry policy for the news provider, honoring the {@code FINAGENT_NEWS_RETRY_*}
     * settings. Separate from the market policy: the two providers have different
     * rate limits and timeout budgets, so sharing one executor would silently ignore
     * the news knobs (Phase 15 audit fix).
     */
    @Bean
    public HttpRetryExecutor newsRetryExecutor(FinAgentProperties properties) {
        var news = properties.getNews();
        return new HttpRetryExecutor(news.getRetryAttempts(), news.getRetryBackoff());
    }

    @Bean
    public NewsDataProvider newsDataProvider(FinAgentProperties properties,
                                             @Qualifier("newsRetryExecutor") HttpRetryExecutor retryExecutor,
                                             RestClient.Builder restClientBuilder) {
        var news = properties.getNews();
        return switch (news.getProvider()) {
            case "newsapi" -> {
                SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
                requestFactory.setConnectTimeout((int) news.getTimeout().toMillis());
                requestFactory.setReadTimeout((int) news.getTimeout().toMillis());
                RestClient.Builder configuredBuilder = restClientBuilder.requestFactory(requestFactory);
                yield new NewsApiAdapter(configuredBuilder, news, retryExecutor);
            }
            case "stub" -> new StubNewsDataProvider();
            default -> throw new IllegalStateException(
                    "Unknown news provider '%s' (supported: stub, newsapi)".formatted(news.getProvider()));
        };
    }
}
