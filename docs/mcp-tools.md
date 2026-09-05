# FINAGENT — MCP Finance Tools (Phase 5)

## What MCP is doing in FinAgent

FinAgent now exposes its existing financial capabilities through the **Model Context Protocol (MCP)**.
MCP is an open protocol (JSON-RPC over HTTP, "Streamable HTTP" transport) that lets MCP-compatible
clients — AI agents, IDEs, the MCP Inspector — discover and invoke tools. FinAgent runs an **MCP
server**, which is an *integration/interface layer only*: every tool delegates to the existing
application services and providers. No business logic lives in the MCP layer.

## Architecture

```
MCP client (agent / inspector / curl)
        │  JSON-RPC over HTTP  →  /mcp  (Streamable HTTP)
        ▼
MCP Server  (Spring AI MCP Server Boot Starter, protocol = STREAMABLE)
        │  ToolCallbackProvider (McpConfiguration)
        ▼
MCP Tools:  get_stock_price · get_stock_history · get_stock_fundamentals · search_financial_news
           analyze_stock_risk · analyze_news_sentiment
        │  (thin, typed @Tool methods — no providers, no HTTP clients)
        ▼
Application Services:  StockDataService · NewsDataService · SentimentAnalysisService
        ▼
Provider interfaces:  MarketDataProvider · NewsDataProvider
        ▼
Adapters:  StubMarketDataProvider / AlphaVantageMarketAdapter · StubNewsDataProvider / NewsApiAdapter
        ▼
External APIs: Alpha Vantage · NewsAPI.org   (keys from environment variables only)
```

**Dependency direction is strictly:** `MCP Tool → Application Service → Provider Interface →
External Provider`. The tools *never* call external APIs directly and contain no credentials.

### Tool registration (verified against Spring AI 1.1.8)

- Tool methods are annotated with Spring AI's current annotations
  `@Tool` / `@ToolParam` (`org.springframework.ai.tool.annotation`).
- `McpConfiguration` wraps the six tool classes in one `ToolCallbackProvider` bean
  (`MethodToolCallbackProvider`). The MCP server auto-configuration converts all
  `ToolCallbackProvider` beans into MCP `SyncToolSpecification`s and registers them.
- Note: Spring AI 1.1.x ships the newer community annotations (`@McpTool`/`@McpArg` in
  `org.springaicommunity.mcp.annotation`) **and** the stable `@Tool`/`@ToolParam` API. This project
  uses `@Tool`/`@ToolParam` — the exact `@McpToolParam` API the roadmap anticipated does not exist
  in the verified version, and `@Tool` is the documented, non-experimental Spring AI tool API.

## Available tools

| Tool | Input (JSON) | Output (clean DTO) | Delegates to |
|---|---|---|---|
| `get_stock_price` | `{"symbol":"AAPL"}` | `StockPriceToolResponse` (symbol, price, change, changePercent, timestamp) | `StockDataService.getQuote` |
| `get_stock_history` | `{"symbol":"AAPL","from":"2026-08-01","to":"2026-08-31"}` (dates optional) | `StockHistoryToolResponse` (symbol, from, to, barCount, bars) | `StockDataService.getHistory` |
| `get_stock_fundamentals` | `{"symbol":"AAPL"}` | `StockFundamentalsToolResponse` (symbol, fundamentals) | `StockDataService.getFundamentals` |
| `search_financial_news` | `{"symbol":"AAPL","from":"...","to":"...","limit":20}` (dates/limit optional) | `FinancialNewsToolResponse` (symbol, from, to, count, articles) | `NewsDataService.getRecentNews` |
| `analyze_stock_risk` | `{"symbol":"AAPL","from":"2026-08-01","to":"2026-08-31","benchmark":"SPY"}` (benchmark/dates optional) | `AnalyzeStockRiskToolResponse` (symbol, from, to, RiskAnalysisResult) | `RiskAnalysisService.analyzeStockRisk` |
| `analyze_news_sentiment` | `{"symbol":"AAPL","from":"...","to":"...","limit":20}` (dates/limit optional) | `NewsSentimentToolResponse` (symbol, from, to, articleCount, sentiment) | `SentimentAnalysisService.analyzeSymbol` |

Notes:
- Outputs reuse the shared domain DTOs (`Quote`, `HistoricalBar`, `Fundamentals`, `NewsArticle`);
  no provider-specific JSON structure ever leaks to MCP clients.
- `search_financial_news` is **pure retrieval** — for scored sentiment call
  `analyze_news_sentiment`, which fetches through the same `NewsDataService` (no
  duplicated retrieval, no direct provider calls) and returns the deterministic
  lexicon result: label (`POSITIVE`/`NEUTRAL`/`NEGATIVE`/`UNAVAILABLE`), score in
  `[-1.0, +1.0]`, confidence, per-label counts and methodology. Provider failures
  surface as `UNAVAILABLE` with a reason, never as fabricated `NEUTRAL`.
  Full methodology: see [`sentiment-analysis.md`](sentiment-analysis.md).
- Dates use ISO-8601 (`yyyy-MM-dd`); defaults: history = last 90 days, news = last 7 days (news
  `limit` default 20, max 50). Invalid input is rejected with a clear error.

### `analyze_stock_risk`

**Implemented in Phase 6.** Deterministic risk analysis for a stock: annualized volatility, maximum
drawdown, beta vs an optional benchmark, Sharpe ratio (only when `finagent.risk.risk-free-rate` is
configured), and a transparent composite risk score (LOW / MODERATE / HIGH). All metrics are
computed by the deterministic analytics engine — never by an LLM. Unavailable metrics are reported
as such (never fabricated). See `docs/risk-methodology.md` for the full methodology.

## Configuration

The MCP server is configured in `backend/src/main/resources/application.yml` under
`spring.ai.mcp.server`:

```yaml
spring:
  ai:
    mcp:
      server:
        name: finagent
        version: 1.0.0
        enabled: ${FINAGENT_MCP_ENABLED:true}
        protocol: STREAMABLE
        request-timeout: 20s
        capabilities:
          tools: true
        streamable-http:
          mcp-endpoint: /mcp
```

- `protocol: STREAMABLE` — the current MCP transport (the framework's default `SSE` is deprecated).
- Endpoint: **`/mcp`** on the application HTTP port (`8080` by default in dev), served by the same
  embedded Tomcat.
- Environment variable: `FINAGENT_MCP_ENABLED=false` disables the server entirely.
- No MCP-specific credentials are needed — tool data comes from the existing providers, whose keys
  stay in environment variables (`ALPHA_VANTAGE_API_KEY`, `NEWS_API_KEY`).

## How to verify the MCP server

1. Start the app (uses the stub providers — no internet/keys required):

   ```bash
   cd backend
   mvn spring-boot:run
   ```

2. Inspect the raw JSON-RPC endpoint (Streamable HTTP; capture and re-send `Mcp-Session-Id`):

   ```bash
   # initialize
   curl -s -D - -X POST http://localhost:8080/mcp \
     -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
   # → note the Mcp-Session-Id response header, then:
   curl -s -X POST http://localhost:8080/mcp \
     -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -H 'Mcp-Session-Id: <session-id>' \
     -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
   # list tools
   curl -s -X POST http://localhost:8080/mcp \
     -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -H 'Mcp-Session-Id: <session-id>' \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
   # call a tool
   curl -s -X POST http://localhost:8080/mcp \
     -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -H 'Mcp-Session-Id: <session-id>' \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"AAPL"}}}'
   ```

3. Or use an MCP-compatible client, e.g. the official **MCP Inspector**
   (`npx @modelcontextprotocol/inspector`), configured to connect to `http://localhost:8080/mcp`
   with the Streamable HTTP transport.

4. Automated verification (no network): `McpServerIntegrationTest` boots the app on a random port
   and runs the full handshake against `/mcp` — asserting the six tools are advertised and invoked,
   and that invalid/unknown/failing inputs surface as MCP errors. Run with:
   `mvn test -Dtest=McpServerIntegrationTest`.

## Testing strategy (no external dependencies)

- **Unit tests** per tool (`mcp/tool/*Test`) mock the application services; they cover valid
  requests, invalid symbols, malformed dates, inverted ranges, empty results, and typed provider
  failures (404 unknown symbol, 502 provider failure, 504 timeout).
- **Integration test** (`McpServerIntegrationTest`) needs no PostgreSQL, Docker, internet, or API
  keys — the stub providers answer every tool call deterministically.

## Security considerations

- The `/mcp` endpoint **is unauthenticated**. It is intended for local/development use only and
  must not be exposed on a public network without a security boundary (e.g. an authenticated
  reverse proxy or application-level auth).
- In production deployments without intentional MCP exposure, set `FINAGENT_MCP_ENABLED=false`.

## Future MCP client / agent integration

- The tools are MCP-native, so any MCP client (Claude Desktop, Spring AI `ChatClient` with
  `spring-ai-starter-mcp-client`, the MCP Inspector) can connect to `http://<host>:8080/mcp` and
  call them.
- FinAgent's own AI agent executes the same application services in-process
  (no self-loop MCP client call — see `AiConfig`); external clients connect
  over HTTP as above.
- `analyze_stock_risk` (deterministic risk engine, Phase 6) and
  `analyze_news_sentiment` (lexicon sentiment, Phase 9) are implemented below.