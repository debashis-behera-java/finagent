package com.finagent.mcp.tool;

import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.mcp.dto.StockHistoryToolResponse;
import com.finagent.service.StockDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockHistoryMcpToolTest {

    @Mock
    private StockDataService stockDataService;

    private StockHistoryMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new StockHistoryMcpTool(stockDataService);
    }

    @Test
    void parsesIsoDatesAndDelegatesToService() {
        HistoricalSeries series = new HistoricalSeries("AAPL", List.of(
                new HistoricalBar(LocalDate.of(2026, 8, 3), new BigDecimal("150.00"),
                        new BigDecimal("151.00"), new BigDecimal("149.00"), new BigDecimal("150.50"), 81_000_000L)));
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        when(stockDataService.getHistory("AAPL", from, to)).thenReturn(series);

        StockHistoryToolResponse response = tool.getStockHistory("AAPL", "2026-08-01", "2026-08-31");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.from()).isEqualTo(from);
        assertThat(response.to()).isEqualTo(to);
        assertThat(response.barCount()).isEqualTo(1);
        assertThat(response.bars()).hasSize(1);
        verify(stockDataService).getHistory(eq("AAPL"), eq(from), eq(to));
    }

    @Test
    void missingDatesArePassedAsNullForServiceDefaults() {
        when(stockDataService.getHistory("AAPL", null, null))
                .thenReturn(new HistoricalSeries("AAPL", List.of()));

        StockHistoryToolResponse response = tool.getStockHistory("AAPL", null, null);

        assertThat(response.from()).isNull();
        assertThat(response.to()).isNull();
        assertThat(response.barCount()).isZero();
    }

    @Test
    void malformedFromDateIsRejected() {
        assertThatThrownBy(() -> tool.getStockHistory("AAPL", "not-a-date", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void malformedToDateIsRejected() {
        assertThatThrownBy(() -> tool.getStockHistory("AAPL", "2026-08-01", "2026-08-31T10:00:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to");
    }

    @Test
    void invertedRangeIsRejectedByServiceLayer() {
        LocalDate from = LocalDate.of(2026, 8, 31);
        LocalDate to = LocalDate.of(2026, 8, 1);
        when(stockDataService.getHistory("AAPL", from, to))
                .thenThrow(new IllegalArgumentException("'from' date must not be after 'to' date"));

        assertThatThrownBy(() -> tool.getStockHistory("AAPL", "2026-08-31", "2026-08-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void emptyHistoryReturnsZeroBarCount() {
        when(stockDataService.getHistory("AAPL", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(new HistoricalSeries("AAPL", List.of()));

        StockHistoryToolResponse response = tool.getStockHistory("AAPL", "2026-08-01", "2026-08-31");

        assertThat(response.barCount()).isZero();
        assertThat(response.bars()).isEmpty();
    }

    @Test
    void invalidSymbolIsRejectedByServiceLayer() {
        when(stockDataService.getHistory("BAD!SYMBOL", null, null))
                .thenThrow(new IllegalArgumentException("Invalid symbol 'BAD!SYMBOL'"));

        assertThatThrownBy(() -> tool.getStockHistory("BAD!SYMBOL", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid symbol");
    }
}