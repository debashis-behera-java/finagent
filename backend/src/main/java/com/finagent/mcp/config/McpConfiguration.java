package com.finagent.mcp.config;

import com.finagent.mcp.tool.AnalyzeStockRiskMcpTool;
import com.finagent.mcp.tool.FinancialNewsMcpTool;
import com.finagent.mcp.tool.NewsSentimentMcpTool;
import com.finagent.mcp.tool.StockFundamentalsMcpTool;
import com.finagent.mcp.tool.StockHistoryMcpTool;
import com.finagent.mcp.tool.StockPriceMcpTool;
import com.finagent.service.NewsDataService;
import com.finagent.service.RiskAnalysisService;
import com.finagent.service.SentimentAnalysisService;
import com.finagent.service.StockDataService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP tool wiring (Phase 5/6/9). Exposes the existing finance services as MCP tools by
 * registering a single {@link ToolCallbackProvider}. The Spring AI MCP server starter
 * auto-collects this provider and publishes every tool at the MCP endpoint.
 *
 * <p>Dependency direction is strictly: MCP tool -> application service -> provider
 * interface -> external provider. The tools below hold no provider clients and make
 * no external API calls themselves.</p>
 */
@Configuration
public class McpConfiguration {

    @Bean
    public ToolCallbackProvider finagentTools(StockDataService stockDataService,
                                              NewsDataService newsDataService,
                                              RiskAnalysisService riskAnalysisService,
                                              SentimentAnalysisService sentimentAnalysisService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        new StockPriceMcpTool(stockDataService),
                        new StockHistoryMcpTool(stockDataService),
                        new StockFundamentalsMcpTool(stockDataService),
                        new FinancialNewsMcpTool(newsDataService),
                        new AnalyzeStockRiskMcpTool(riskAnalysisService),
                        new NewsSentimentMcpTool(sentimentAnalysisService))
                .build();
    }
}