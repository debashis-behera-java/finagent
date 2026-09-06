package com.finagent.service;

import com.finagent.exception.ResourceNotFoundException;
import com.finagent.market.MarketDataProvider;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockDataServiceTest {

    @Mock
    private MarketDataProvider marketDataProvider;

    @InjectMocks
    private StockDataService service;

    @Test
    void normalizesSymbolBeforeDelegating() {
        service.getQuote("  aapl ");

        verify(marketDataProvider).getQuote("AAPL");
    }

    @Test
    void overviewCombinesQuoteAndFundamentals() {
        Quote quote = new Quote("AAPL", new BigDecimal("150.50"),
                new BigDecimal("0.70"), new BigDecimal("0.4673"), null);
        Fundamentals fundamentals = new Fundamentals("AAPL", "Apple Inc", "Technology",
                null, null, null, null, null, null);
        when(marketDataProvider.getQuote("AAPL")).thenReturn(quote);
        when(marketDataProvider.getFundamentals("AAPL")).thenReturn(fundamentals);

        StockResponse response = service.getStockOverview("aapl");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.quote()).isSameAs(quote);
        assertThat(response.fundamentals()).isSameAs(fundamentals);
    }

    @Test
    void invalidSymbolIsRejected() {
        assertThatThrownBy(() -> service.getQuote("TOO_LONG_SYMBOL_123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid symbol");
        assertThatThrownBy(() -> service.getQuote("BAD!SYMBOL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyDefaultsToLastNinetyDaysWhenRangeMissing() {
        when(marketDataProvider.getHistory(anyString(), any(), any())).thenReturn(null);

        service.getHistory("AAPL", null, null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(marketDataProvider).getHistory(org.mockito.ArgumentMatchers.eq("AAPL"),
                from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(LocalDate.now());
        assertThat(from.getValue()).isEqualTo(LocalDate.now().minusDays(90));
    }

    @Test
    void historyRangeValidationRejectsInvertedRange() {
        assertThatThrownBy(() -> service.getHistory("AAPL",
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void providerUnknownSymbolPropagates() {
        when(marketDataProvider.getQuote("NOPE"))
                .thenThrow(new ResourceNotFoundException("Unknown symbol: NOPE"));

        assertThatThrownBy(() -> service.getQuote("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
