# FINAGENT — Deterministic Risk Methodology (Phase 6)

## Purpose

This document describes FinAgent's **deterministic financial analytics and risk-analysis
engine**. The engine calculates quantitative risk metrics from validated market data using
transparent, published formulas. These calculations are **never performed by an LLM** and are
never fabricated: every number is derived from real (or stubbed, in dev/test) data, and anything
that cannot be derived is reported as explicitly unavailable with a reason.

## Architecture

```
StockDataService / BenchmarkDataProvider
        |  Validated historical data (closing prices)
        v
  ReturnCalculator            r_t = (P_t / P_{t-1}) - 1
        |
        v
  VolatilityCalculator        sigma_annual = sigma_sample(r) * sqrt(tradingDaysPerYear)
  MaxDrawdownCalculator       max (peak_t - P_t) / peak_t
  BetaCalculator              Cov(r_stock, r_benchmark) / Var(r_benchmark)
  SharpeRatioCalculator       (annualizedReturn - riskFreeRate) / annualizedVolatility
        |
        v
  PortfolioDiversificationAnalyzer    HHI of weights + sector HHI
        |
        v
  RiskScoringService          weighted, normalized composite (0-100) + category
        |
        v
  RiskAnalysisResult
```

**Separation of concerns:** the `analysis/calculator` classes are *pure functions* (no Spring, no
state, no I/O). `RiskAnalysisService` (in `service/`) is the thin orchestrator that fetches data,
aligns series, and assembles the result. The MCP tool `analyze_stock_risk` calls only the service —
it performs no calculations itself.

## Metrics, Formulas, and Assumptions

All calculations assume **daily** periodic data unless otherwise stated. The annualization factor is
configurable via `finagent.risk.trading-days-per-year` (default **252**, the conventional number of
US trading days per year).

### 1. Periodic (simple) returns

```
r_t = (P_t / P_{t-1}) - 1      for t = 1 .. n-1
```

- Input: chronological closing prices (all strictly positive).
- Output: `n-1` returns. Empty / single-price series yield an empty return series (treated as
  insufficient data upstream).
- Invalid input (null list, null or non-positive price) throws `IllegalArgumentException`.

### 2. Annualized volatility

```
sigma_annual = sigma_sample(r) * sqrt(tradingDaysPerYear)
```

- `sigma_sample` is the **sample** standard deviation (denominator `n-1`).
- Zero variance is a valid result (annualized volatility of 0), not an error.
- Fewer than 2 returns → **unavailable** ("Insufficient observations").

### 3. Maximum drawdown

For each observation `t` with rolling peak `peak_t = max(P_1 .. P_t)`:

```
DD_t     = (peak_t - P_t) / peak_t    (0 when P_t == peak_t)
MaxDrawdown = max(DD_t)
```

Reported as a **positive fraction** (e.g. `0.1842` == 18.42% loss), with the peak value/date and
trough value/date of the worst drawdown episode. Requires at least 2 observations.

### 4. Beta (relative to a benchmark)

```
beta = Cov(r_stock, r_benchmark) / Var(r_benchmark)
```

- Computed from **returns**, not raw prices.
- Stock and benchmark series are **aligned by date** (inner join) and must be pairwise aligned.
- Requires at least `finagent.risk.minObservations` aligned pairs (default **2**).
- Zero benchmark variance → **unavailable** ("Beta is undefined: benchmark variance is zero"). Beta is
  never guessed.
- Data source: `BenchmarkDataProvider` port. Today only the deterministic `StubBenchmarkDataProvider`
  ships; a real adapter (e.g. using the Alpha Vantage adapter for an index symbol such as `SPY` or
  `^GSPC`) connects later via `finagent.risk.benchmark-provider` without touching business code.

### 5. Sharpe ratio (annualized)

```
annualizedReturn     = mean(r) * tradingDaysPerYear
annualizedVolatility = sigma_sample(r) * sqrt(tradingDaysPerYear)
Sharpe               = (annualizedReturn - riskFreeRate) / annualizedVolatility
```

- **Risk-free rate assumption:** the annual rate is a *configuration value*
  (`finagent.risk.risk-free-rate`, env `FINAGENT_RISK_RISK_FREE_RATE`). It is **NOT** silently
  invented. When it is not configured, the Sharpe ratio is reported as **unavailable** ("Risk-free
  rate not configured") rather than fabricating a number.
- Zero volatility → unavailable ("Sharpe ratio is undefined: zero return variance").
- Insufficient observations → unavailable.

### 6. Portfolio diversification

Deterministic analysis of a set of `{ticker, sector, weight}` holdings:

- Non-positive / zero-weight holdings are excluded.
- Weights are **normalized** to sum to 1 (the raw sum is reported; `normalized=true` when the raw
  sum ≠ 1).
- **Largest holding** = max normalized weight.
- **Herfindahl-Hirschman Index** `HHI = sum(w_i^2)` over holdings (0..1; 1 = fully concentrated).
- **Sector concentration** uses the same HHI over grouped sector weights. Missing sectors are grouped
  under an `UNKNOWN` label and counted (`unknownSectorCount`) — sector information is never invented.
- No positive-weight holdings → unavailable.

### 7. Composite risk score

A transparent weighted composite of the available metrics, normalized to **0..100**.

Each available metric is first mapped to a 0..1 "risk component":

| Metric | Component formula | "high risk" anchor |
|---|---|---|
| Volatility | `min(annualizedVolatility / 0.30, 1)` | 30% annualized |
| Max drawdown | `min(maxDrawdown / 0.50, 1)` | 50% drawdown |
| Beta | `beta < 0 → 0; else min(beta / 2.0, 1)` | beta = 2 |
| Sharpe | `clamp((1 - sharpe) / 3, 0, 1)` | Sharpe 1 → low, -2 → high |
| Concentration | `HHI` (0..1 as-is) | HHI |

**Weights** (relative): volatility **0.30**, max drawdown **0.25**, beta **0.15**, sharpe **0.15**,
concentration **0.15**. When a metric is unavailable, its weight is **re-distributed proportionally**
among the available metrics (the omission is recorded in the explanation). A score is never produced
from zero metrics.

**Score** (rounded to 2 dp) = weighted sum of components × 100.

## Risk categories

| Category | Score range |
|---|---|
| LOW | [0, 33.33) |
| MODERATE | [33.33, 66.66] |
| HIGH | (66.66, 100] |

## Missing-data behavior

The engine never fabricates prices, returns, volatility, beta, Sharpe ratios, weights, or sector
information. When required data is unavailable, the corresponding sub-result carries
`available=false` and a human-readable `reason`:

- Empty / single-price series → volatility/drawdown/beta/sharpe unavailable.
- Benchmark fetch failure or zero benchmark variance → beta unavailable.
- Unconfigured risk-free rate → Sharpe unavailable.
- No positive-weight holdings → diversification unavailable.
- No metrics at all → composite risk score unavailable ("No risk metrics available").

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `FINAGENT_RISK_RISK_FREE_RATE` | unset (null) | Annual risk-free rate; unset → Sharpe unavailable |
| `FINAGENT_RISK_TRADING_DAYS_PER_YEAR` | 252 | Annualization factor |
| `FINAGENT_RISK_MIN_OBSERVATIONS` | 2 | Min aligned pairs for beta |
| `FINAGENT_RISK_DEFAULT_BENCHMARK` | SPY | Benchmark when caller omits one |
| `FINAGENT_RISK_BENCHMARK_PROVIDER` | stub | Benchmark data source (real adapter later) |

## Examples

For daily closing prices `100, 110, 105, 120, 115`:

- Returns: `0.10, -0.04545, 0.14286, -0.04167`
- Max drawdown: `(110 - 105) / 110 = 0.04545` (4.55%), peak at 110, trough at 105.

For holdings `[{AAPL,Tech,0.25},{MSFT,Tech,0.25},{JNJ,Health,0.25},{XOM,Energy,0.25}]`:

- HHI = 0.25 (well diversified), sector HHI = 0.3125, largest holding = 0.25.

## Limitations

- Daily-data annualization is assumed; intraday or weekly frequencies are not currently modeled.
- Beta requires an external benchmark; only a deterministic stub is wired today.
- The risk-free rate is configurable but static (no term-structure modeling).
- Risk-score thresholds are fixed and published above; they are not tuned to any specific portfolio.
- All inputs must come from validated data; the engine does not override provider exceptions except
  to translate a benchmark failure into an "unavailable beta" (the rest of the analysis still completes).