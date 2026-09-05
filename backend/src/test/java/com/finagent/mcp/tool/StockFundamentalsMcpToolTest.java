package com.finagent.mcp.tool;

import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.market.dto.Fundamentals;
import com.finagent.mcp.dto.StockFundamentalsToolResponse;
import com.finagent.service.StockDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockFundamentalsMcpToolTest {

    @Mock
    private StockDataService stockDataService;

    private StockFundamentalsMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new StockFundamentalsMcpTool(stockDataService);
    }

    @Test
    void returnsFundamentalsForValidSymbol() {
        Fundamentals fundamentals = new Fundamentals("AAPL", "Apple Inc", "Technology",
                "Consumer Electronics", null, null, null, null, "Apple designs devices.");
        when(stockDataService.getFundamentals("AAPL")).thenReturn(fundamentals);

        StockFundamentalsToolResponse response = tool.getStockFundamentals("AAPL");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.fundamentals()).isSameAs(fundamentals);
        verify(stockDataService).getFundamentals("AAPL");
    }

    @Test
    void unknownSymbolPropagates() {
        when(stockDataService.getFundamentals("NOPE"))
                .thenThrow(new UnknownSymbolException("NOPE"));

        assertThatThrownBy(() -> tool.getStockFundamentals("NOPE"))
                .isInstanceOf(UnknownSymbolException.class)
                .hasMessageContaining("Unknown symbol");
    }

    @Test
    void providerFailurePropagates() {
        when(stockDataService.getFundamentals("FAIL"))
                .thenThrow(new ProviderException("Upstream provider failed"));

        assertThatThrownBy(() -> tool.getStockFundamentals("FAIL"))
                .isInstanceOf(ProviderException.class);
    }
}