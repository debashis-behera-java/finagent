package com.finagent.mcp.tool;

import com.finagent.exception.ProviderException;
import com.finagent.exception.ProviderTimeoutException;
import com.finagent.exception.ResourceNotFoundException;
import com.finagent.market.dto.Quote;
import com.finagent.mcp.dto.StockPriceToolResponse;
import com.finagent.service.StockDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockPriceMcpToolTest {

    @Mock
    private StockDataService stockDataService;

    private StockPriceMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new StockPriceMcpTool(stockDataService);
    }

    @Test
    void returnsPriceResponseForValidSymbol() {
        Quote quote = new Quote("AAPL", new BigDecimal("150.50"), new BigDecimal("0.70"),
                new BigDecimal("0.4673"), Instant.parse("2026-08-28T15:30:00Z"));
        when(stockDataService.getQuote("AAPL")).thenReturn(quote);

        StockPriceToolResponse response = tool.getStockPrice("AAPL");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.price()).isEqualByComparingTo("150.50");
        assertThat(response.change()).isEqualByComparingTo("0.70");
        assertThat(response.changePercent()).isEqualByComparingTo("0.4673");
        assertThat(response.timestamp()).isNotNull();
        verify(stockDataService).getQuote("AAPL");
    }

    @Test
    void invalidSymbolIsRejectedByServiceLayer() {
        when(stockDataService.getQuote("AAPL"))
                .thenThrow(new IllegalArgumentException("Invalid symbol 'AAPL'"));

        assertThatThrownBy(() -> tool.getStockPrice("AAPL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid symbol");
    }

    @Test
    void nullSymbolIsRejected() {
        when(stockDataService.getQuote(null))
                .thenThrow(new IllegalArgumentException("Invalid symbol 'null'"));

        assertThatThrownBy(() -> tool.getStockPrice(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSymbolPropagates() {
        when(stockDataService.getQuote("NOPE"))
                .thenThrow(new ResourceNotFoundException("Unknown symbol: NOPE"));

        assertThatThrownBy(() -> tool.getStockPrice("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Unknown symbol");
    }

    @Test
    void providerFailurePropagates() {
        when(stockDataService.getQuote("FAIL"))
                .thenThrow(new ProviderException("Upstream provider failed"));

        assertThatThrownBy(() -> tool.getStockPrice("FAIL"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void providerTimeoutPropagates() {
        when(stockDataService.getQuote("SLOW"))
                .thenThrow(new ProviderTimeoutException("Upstream provider timed out", null));

        assertThatThrownBy(() -> tool.getStockPrice("SLOW"))
                .isInstanceOf(ProviderTimeoutException.class)
                .isInstanceOf(ProviderException.class);
    }
}