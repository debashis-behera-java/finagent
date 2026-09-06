package com.finagent.market.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.common.HttpRetryExecutor;
import com.finagent.config.FinAgentProperties;
import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.market.MarketDataProvider;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Alpha Vantage implementation of the {@link MarketDataProvider} port.
 * API key comes exclusively from configuration/env (ALPHA_VANTAGE_API_KEY).
 * Transport settings (timeouts) are applied by the configuration layer on the builder
 * BEFORE this adapter builds its client, so test doubles (MockRestServiceServer) keep working.
 * Failures are translated into typed exceptions (never leaked RestClient types).
 */
public class AlphaVantageMarketAdapter implements MarketDataProvider {

    private final RestClient restClient;
    private final String apiKey;
    private final HttpRetryExecutor retry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AlphaVantageMarketAdapter(RestClient.Builder restClientBuilder,
                                     FinAgentProperties.Market config,
                                     HttpRetryExecutor retryExecutor) {
        this.restClient = restClientBuilder
                .baseUrl(config.getBaseUrl())
                .build();
        this.apiKey = config.getApiKey();
        this.retry = retryExecutor;
    }

    @Override
    public Quote getQuote(String symbol) {
        JsonNode root = fetchJson("GLOBAL_QUOTE", symbol, null);
        if (root.hasNonNull("Note") || root.hasNonNull("Information")) {
            throw new ProviderException("Market provider rate limit reached");
        }
        if (root.hasNonNull("Error Message")) {
            throw new UnknownSymbolException(symbol);
        }
        JsonNode globalQuote = root.path("Global Quote");
        if (!globalQuote.hasNonNull("05. price")) {
            throw new UnknownSymbolException(symbol);
        }
        return new Quote(
                globalQuote.path("01. symbol").asText(symbol),
                parseDecimal(globalQuote, "05. price"),
                parseDecimal(globalQuote, "09. change"),
                parsePercent(globalQuote, "10. change percent"),
                parseDateAsInstant(globalQuote, "07. latest trading day"));
    }

    @Override
    public HistoricalSeries getHistory(String symbol, LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(90);
        String outputSize = ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) > 100 ? "full" : "compact";

        JsonNode root = fetchJson("TIME_SERIES_DAILY", symbol, outputSize);
        if (root.hasNonNull("Note") || root.hasNonNull("Information") || root.hasNonNull("Error Message")) {
            throw new ProviderException("Market provider rejected history request for " + symbol);
        }
        JsonNode series = root.path("Time Series (Daily)");
        if (!series.isObject() || series.isEmpty()) {
            throw new UnknownSymbolException(symbol);
        }

        List<HistoricalBar> bars = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = series.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            final LocalDate date;
            try {
                date = LocalDate.parse(entry.getKey());
            } catch (DateTimeParseException ex) {
                // Malformed upstream date key: skip the bar instead of failing the request.
                continue;
            }
            if (date.isBefore(effectiveFrom) || date.isAfter(effectiveTo)) {
                continue;
            }
            JsonNode bar = entry.getValue();
            bars.add(new HistoricalBar(
                    date,
                    parseDecimal(bar, "1. open"),
                    parseDecimal(bar, "2. high"),
                    parseDecimal(bar, "3. low"),
                    parseDecimal(bar, "4. close"),
                    bar.path("5. volume").asLong(0L)));
        }
        bars.sort(Comparator.comparing(HistoricalBar::date));
        return new HistoricalSeries(symbol, bars);
    }

    @Override
    public Fundamentals getFundamentals(String symbol) {
        JsonNode root = fetchJson("OVERVIEW", symbol, null);
        if (root.hasNonNull("Note") || root.hasNonNull("Information")) {
            throw new ProviderException("Market provider rate limit reached");
        }
        if (!root.hasNonNull("Symbol")) {
            throw new UnknownSymbolException(symbol);
        }
        return new Fundamentals(
                root.path("Symbol").asText(symbol),
                parseText(root, "Name"),
                parseText(root, "Sector"),
                parseText(root, "Industry"),
                parseDecimal(root, "MarketCapitalization"),
                parseDecimal(root, "PERatio"),
                parsePercent(root, "DividendYield"),
                parseDecimal(root, "EPS"),
                parseText(root, "Description"));
    }

    private JsonNode fetchJson(String function, String symbol, String outputSize) {
        String body;
        try {
            body = retry.execute(() -> restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/query")
                                .queryParam("function", function)
                                .queryParam("symbol", symbol)
                                .queryParam("apikey", apiKey);
                        if (outputSize != null) {
                            builder.queryParam("outputsize", outputSize);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(String.class));
        } catch (HttpStatusCodeException ex) {
            // Upstream HTTP failures (429 rate limit, 401 key, 5xx outage) are provider
            // failures (HTTP 502 downstream), never internal errors (mirrors NewsApiAdapter).
            throw translateHttpError(ex);
        }
        if (body == null || body.isBlank()) {
            throw new ProviderException("Empty response from market provider");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ProviderException("Malformed response from market provider", e);
        }
    }

    private ProviderException translateHttpError(HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        if (status == 429) {
            return new ProviderException("Market provider rate limit reached (HTTP 429)");
        }
        if (status == 401 || status == 403) {
            return new ProviderException("Market provider rejected the API key (HTTP " + status + ")");
        }
        return new ProviderException("Market provider returned HTTP " + status, ex);
    }

    private BigDecimal parseDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()
                || "-".equals(value.asText())) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().replace(",", ""));
        } catch (NumberFormatException ex) {
            // Non-numeric upstream payload ("N/A", "None", ...) means the field is
            // unavailable, not a server error — degrade to null like a missing field.
            return null;
        }
    }

    private BigDecimal parsePercent(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().replace("%", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String parseText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Instant parseDateAsInstant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.asText()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}


