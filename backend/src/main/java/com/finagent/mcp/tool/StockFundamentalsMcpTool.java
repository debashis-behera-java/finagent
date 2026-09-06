package com.finagent.mcp.tool;

import com.finagent.market.dto.Fundamentals;
import com.finagent.mcp.dto.StockFundamentalsToolResponse;
import com.finagent.service.StockDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * MCP tool {@code get_stock_fundamentals}: delegates to {@link StockDataService}.
 */
@Slf4j
public class StockFundamentalsMcpTool {

    private final StockDataService stockDataService;

    public StockFundamentalsMcpTool(StockDataService stockDataService) {
        this.stockDataService = stockDataService;
    }

    @Tool(name = "get_stock_fundamentals",
            description = "Get company fundamentals for a ticker symbol (name, sector, industry, market cap, "
                    + "PE ratio, dividend yield, EPS, description). Missing fields stay null - never guessed.")
    public StockFundamentalsToolResponse getStockFundamentals(
            @ToolParam(description = "Stock ticker symbol, e.g. AAPL or MSFT") String symbol) {
        log.debug("MCP get_stock_fundamentals invoked for symbol={}", symbol);
        Fundamentals fundamentals = stockDataService.getFundamentals(symbol);
        log.debug("MCP get_stock_fundamentals resolved symbol={} sector={}", fundamentals.symbol(), fundamentals.sector());
        return new StockFundamentalsToolResponse(fundamentals.symbol(), fundamentals);
    }
}