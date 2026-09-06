package com.finagent.market.adapter;

import com.finagent.common.HttpRetryExecutor;
import com.finagent.config.FinAgentProperties;
import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlphaVantageMarketAdapterTest {

    private MockRestServiceServer server;
    private AlphaVantageMarketAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        FinAgentProperties properties = new FinAgentProperties();
        properties.getMarket().setApiKey("test-key");
        properties.getMarket().setTimeout(Duration.ofSeconds(2));
        int attempts = 1;
        adapter = new AlphaVantageMarketAdapter(builder, properties.getMarket(),
                new HttpRetryExecutor(attempts, Duration.ofMillis(1)));
    }

    @Test
    void getQuoteParsesGlobalQuoteResponse() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("GLOBAL_QUOTE")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "Global Quote": {
                            "01. symbol": "AAPL",
                            "05. price": "150.50",
                            "07. latest trading day": "2026-08-28",
                            "09. change": "0.70",
                            "10. change percent": "0.4673%"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var quote = adapter.getQuote("AAPL");

        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.price()).isEqualByComparingTo(new BigDecimal("150.50"));
        assertThat(quote.change()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(quote.changePercent()).isEqualByComparingTo(new BigDecimal("0.4673"));
        assertThat(quote.timestamp()).isNotNull();
        server.verify();
    }

    @Test
    void getQuoteMapsRateLimitNoteToProviderException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("GLOBAL_QUOTE")))
                .andRespond(withSuccess("{\"Note\": \"API call frequency limit\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getQuote("AAPL"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void getQuoteWithEmptyGlobalQuoteThrowsUnknownSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("GLOBAL_QUOTE")))
                .andRespond(withSuccess("{\"Global Quote\": {}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getQuote("NOPE"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    void getFundamentalsParsesOverviewResponse() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("OVERVIEW")))
                .andRespond(withSuccess("""
                        {
                          "Symbol": "AAPL",
                          "Name": "Apple Inc",
                          "Sector": "Technology",
                          "Industry": "Consumer Electronics",
                          "MarketCapitalization": "2900000000000.00",
                          "PERatio": "31.5",
                          "DividendYield": "0.0044",
                          "EPS": "6.13",
                          "Description": "Apple designs smartphones."
                        }
                        """, MediaType.APPLICATION_JSON));

        var fundamentals = adapter.getFundamentals("AAPL");

        assertThat(fundamentals.companyName()).isEqualTo("Apple Inc");
        assertThat(fundamentals.sector()).isEqualTo("Technology");
        assertThat(fundamentals.marketCap()).isEqualByComparingTo(new BigDecimal("2900000000000.00"));
        assertThat(fundamentals.peRatio()).isEqualByComparingTo(new BigDecimal("31.5"));
        assertThat(fundamentals.eps()).isEqualByComparingTo(new BigDecimal("6.13"));
        assertThat(fundamentals.description()).isNotBlank();
    }

    @Test
    void getHistoryParsesBarsAndFiltersDateRange() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("TIME_SERIES_DAILY")))
                .andRespond(withSuccess("""
                        {
                          "Meta Data": {"2. Symbol": "AAPL"},
                          "Time Series (Daily)": {
                            "2026-08-28": {"1. open": "150.00", "2. high": "151.00", "3. low": "149.00",
                                           "4. close": "150.50", "5. volume": "81000000"},
                            "2026-08-27": {"1. open": "149.00", "2. high": "150.00", "3. low": "148.00",
                                           "4. close": "149.80", "5. volume": "75000000"},
                            "2026-07-01": {"1. open": "140.00", "2. high": "141.00", "3. low": "139.00",
                                           "4. close": "140.50", "5. volume": "65000000"}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var series = adapter.getHistory("AAPL",
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 28));

        assertThat(series.symbol()).isEqualTo("AAPL");
        assertThat(series.bars()).hasSize(2); // 2026-07-01 filtered out
        assertThat(series.bars().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(series.bars().get(1).date()).isEqualTo(LocalDate.of(2026, 8, 28));
        assertThat(series.bars().get(1).close()).isEqualByComparingTo(new BigDecimal("150.50"));
        assertThat(series.bars().get(1).volume()).isEqualTo(81_000_000L);
    }

    @Test
    void getHistoryWithEmptySeriesThrowsUnknownSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("TIME_SERIES_DAILY")))
                .andRespond(withSuccess("{\"Meta Data\": {}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getHistory("NOPE", null, null))
                .isInstanceOf(UnknownSymbolException.class);
    }
}

