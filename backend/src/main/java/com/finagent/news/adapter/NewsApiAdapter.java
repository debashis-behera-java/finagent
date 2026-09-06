package com.finagent.news.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.common.HttpRetryExecutor;
import com.finagent.config.FinAgentProperties;
import com.finagent.exception.ProviderException;
import com.finagent.news.NewsDataProvider;
import com.finagent.news.dto.NewsArticle;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * NewsAPI.org implementation of the {@link NewsDataProvider} port.
 * API key comes exclusively from configuration/env (NEWS_API_KEY).
 * Normalizes provider payloads into {@link NewsArticle}; maps rate limits (HTTP 429/426 and
 * status=error/code=rateLimited) and other failures into typed exceptions.
 */
public class NewsApiAdapter implements NewsDataProvider {

    private static final int MAX_PAGE_SIZE = 100;

    private final RestClient restClient;
    private final String apiKey;
    private final HttpRetryExecutor retry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NewsApiAdapter(RestClient.Builder restClientBuilder,
                          FinAgentProperties.News config,
                          HttpRetryExecutor retryExecutor) {
        this.restClient = restClientBuilder
                .baseUrl(config.getBaseUrl())
                .build();
        this.apiKey = config.getApiKey();
        this.retry = retryExecutor;
    }

    @Override
    public List<NewsArticle> searchNews(String query, LocalDate from, LocalDate to, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        String body = fetchEverything(query, from, to, pageSize);
        JsonNode root = parse(body);

        if ("error".equals(root.path("status").asText())) {
            String code = root.path("code").asText("unknown");
            String message = root.path("message").asText("no details");
            if ("rateLimited".equals(code)) {
                throw new ProviderException("News provider rate limit reached");
            }
            throw new ProviderException("News provider error (%s): %s".formatted(code, message));
        }

        List<NewsArticle> articles = new ArrayList<>();
        for (JsonNode article : root.path("articles")) {
            String title = article.path("title").asText(null);
            String url = article.path("url").asText(null);
            if (title == null || title.isBlank() || url == null || url.isBlank()) {
                continue; // provider occasionally returns placeholder rows "[Removed]"
            }
            articles.add(new NewsArticle(
                    query,
                    title,
                    url,
                    article.path("source").path("name").asText(null),
                    article.path("description").asText(null),
                    article.path("urlToImage").asText(null),
                    parseInstant(article.path("publishedAt"))));
            if (articles.size() >= limit) {
                break;
            }
        }
        return articles;
    }

    private String fetchEverything(String query, LocalDate from, LocalDate to, int pageSize) {
        try {
            return retry.execute(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v2/everything")
                            .queryParam("q", query)
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .queryParam("pageSize", pageSize)
                            .queryParam("sortBy", "publishedAt")
                            .queryParam("language", "en")
                            .queryParam("apiKey", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class));
        } catch (HttpStatusCodeException ex) {
            throw translateHttpError(ex);
        }
    }

    private ProviderException translateHttpError(HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        if (status == 429 || status == 426) {
            return new ProviderException("News provider rate limit reached (HTTP " + status + ")");
        }
        if (status == 401 || status == 403) {
            return new ProviderException("News provider rejected the API key (HTTP " + status + ")");
        }
        return new ProviderException("News provider returned HTTP " + status, ex);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new ProviderException("Empty response from news provider");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ProviderException("Malformed response from news provider", e);
        }
    }

    private Instant parseInstant(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return null; // never fail the whole response over one malformed timestamp
        }
    }
}
