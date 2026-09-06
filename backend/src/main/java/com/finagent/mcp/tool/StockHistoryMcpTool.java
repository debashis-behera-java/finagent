package com.finagent.mcp.tool;

import com.finagent.market.dto.HistoricalSeries;
import com.finagent.mcp.dto.StockHistoryToolResponse;
import com.finagent.service.StockDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;

/**
 * MCP tool {@code get_stock_history}: delegates to {@link StockDataService}.
 * Optional ISO-8601 date strings are converted here; the service owns range defaults,
 * validation and provider calls.
 */
@Slf4j
public class StockHistoryMcpTool {

    private final StockDataService stockDataService;

    public StockHistoryMcpTool(StockDataService stockDataService) {
        this.stockDataService = stockDataService;
    }

    @Tool(name = "get_stock_history",
            description = "Get daily historical OHLCV bars for a ticker symbol within an inclusive ISO-8601 date range [from, to]. "
                    + "Missing dates default to the trailing 90 days. Bars are sorted ascending by date.")
    public StockHistoryToolResponse getStockHistory(
            @ToolParam(description = "Stock ticker symbol, e.g. AAPL or MSFT") String symbol,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd (optional, default = last 90 days)", required = false) String from,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd (optional, default = today)", required = false) String to) {
        log.debug("MCP get_stock_history invoked for symbol={} from={} to={}", symbol, from, to);
        LocalDate fromDate = DateParsers.parseIsoDate(from, "from");
        LocalDate toDate = DateParsers.parseIsoDate(to, "to");
        HistoricalSeries series = stockDataService.getHistory(symbol, fromDate, toDate);
        log.debug("MCP get_stock_history resolved symbol={} bars={}", series.symbol(), series.bars().size());
        return new StockHistoryToolResponse(
                series.symbol(), fromDate, toDate, series.bars().size(), series.bars());
    }
}