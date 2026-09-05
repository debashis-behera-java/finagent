package com.finagent.mcp.tool;

import com.finagent.market.dto.Quote;
import com.finagent.mcp.dto.StockPriceToolResponse;
import com.finagent.service.StockDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * MCP tool {@code get_stock_price}: delegates to {@link StockDataService} - the tool
 * never calls an external provider directly. Input validation and typed exceptions
 * come from the existing service layer.
 */
@Slf4j
public class StockPriceMcpTool {

    private final StockDataService stockDataService;

    public StockPriceMcpTool(StockDataService stockDataService) {
        this.stockDataService = stockDataService;
    }

    @Tool(name = "get_stock_price",
            description = "Get the latest validated stock price (quote) for a ticker symbol. "
                    + "Returns symbol, price, absolute change, percent change and timestamp.")
    public StockPriceToolResponse getStockPrice(
            @ToolParam(description = "Stock ticker symbol, e.g. AAPL or MSFT") String symbol) {
        log.debug("MCP get_stock_price invoked for symbol={}", symbol);
        Quote quote = stockDataService.getQuote(symbol);
        log.debug("MCP get_stock_price resolved symbol={} price={}", quote.symbol(), quote.price());
        return new StockPriceToolResponse(
                quote.symbol(), quote.price(), quote.change(), quote.changePercent(), quote.timestamp());
    }
}