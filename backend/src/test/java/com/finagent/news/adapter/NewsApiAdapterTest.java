package com.finagent.news.adapter;

import com.finagent.common.HttpRetryExecutor;
import com.finagent.config.FinAgentProperties;
import com.finagent.exception.ProviderException;
import com.finagent.exception.ProviderTimeoutException;
import com.finagent.news.dto.NewsArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NewsApiAdapterTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 21);
    private static final LocalDate TO = LocalDate.of(2026, 8, 28);

    private MockRestServiceServer server;
    private NewsApiAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        FinAgentProperties properties = new FinAgentProperties();
        properties.getNews().setApiKey("test-key");
        properties.getNews().setTimeout(Duration.ofSeconds(2));
        adapter = new NewsApiAdapter(builder, properties.getNews(),
                new HttpRetryExecutor(1, Duration.ofMillis(1)));
    }

    @Test
    void searchNewsNormalizesArticlesResponse() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status": "ok",
                          "totalResults": 2,
                          "articles": [
                            {
                              "source": {"name": "Reuters"},
                              "title": "Apple beats earnings expectations",
                              "description": "Apple reported results above consensus.",
                              "url": "https://example.com/apple-earnings",
                              "urlToImage": "https://example.com/apple-earnings.jpg",
                              "publishedAt": "2026-08-27T16:30:00Z"
                            },
                            {
                              "source": {"name": "Bloomberg"},
                              "title": "[Removed]",
                              "description": null,
                              "url": null,
                              "publishedAt": null
                            },
                            {
                              "source": {"name": "CNBC"},
                              "title": "Analysts raise Apple price targets",
                              "description": "Several banks lifted targets.",
                              "url": "https://example.com/apple-targets",
                              "publishedAt": "2026-08-26T09:00:00Z"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<NewsArticle> articles = adapter.searchNews("AAPL", FROM, TO, 20);

        assertThat(articles).hasSize(2); // "[Removed]" placeholder row skipped
        assertThat(articles.get(0).title()).isEqualTo("Apple beats earnings expectations");
        assertThat(articles.get(0).source()).isEqualTo("Reuters");
        assertThat(articles.get(0).url()).isEqualTo("https://example.com/apple-earnings");
        assertThat(articles.get(0).description()).contains("above consensus");
        assertThat(articles.get(0).imageUrl()).isEqualTo("https://example.com/apple-earnings.jpg");
        assertThat(articles.get(0).publishedAt()).isNotNull();
        assertThat(articles.get(0).query()).isEqualTo("AAPL");
        assertThat(articles.get(1).publishedAt())
                .isEqualTo(java.time.Instant.parse("2026-08-26T09:00:00Z"));
        server.verify();
    }

    @Test
    void statusErrorWithRateLimitedCodeMapsToRateLimitMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withSuccess("""
                        {"status": "error", "code": "rateLimited", "message": "You made too many requests"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.searchNews("AAPL", FROM, TO, 20))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void http429MapsToRateLimitMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}"));

        assertThatThrownBy(() -> adapter.searchNews("AAPL", FROM, TO, 20))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void http401MapsToApiKeyMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{}"));

        assertThatThrownBy(() -> adapter.searchNews("AAPL", FROM, TO, 20))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void statusErrorWithOtherCodeMapsToProviderException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withSuccess("""
                        {"status": "error", "code": "parameterInvalid", "message": "bad parameter"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.searchNews("AAPL", FROM, TO, 20))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("parameterInvalid");
    }

    @Test
    void emptyArticlesListReturnsEmptyResult() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withSuccess("""
                        {"status": "ok", "totalResults": 0, "articles": []}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.searchNews("AAPL", FROM, TO, 20)).isEmpty();
    }

    @Test
    void malformedResponseThrowsProviderException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withSuccess("this is not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.searchNews("AAPL", FROM, TO, 20))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void timeoutMapsToProviderTimeoutException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/everything")))
                .andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> adapter.searchNews("AAPL", FROM, TO, 20))
                .isInstanceOf(ProviderTimeoutException.class)
                .isInstanceOf(ProviderException.class);
    }
}


