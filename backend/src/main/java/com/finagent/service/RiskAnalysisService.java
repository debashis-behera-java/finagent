package com.finagent.service;

import com.finagent.analysis.PortfolioDiversificationAnalyzer;
import com.finagent.analysis.RiskScoringService;
import com.finagent.analysis.calculator.BetaCalculator;
import com.finagent.analysis.calculator.MaxDrawdownCalculator;
import com.finagent.analysis.calculator.ReturnCalculator;
import com.finagent.analysis.calculator.SharpeRatioCalculator;
import com.finagent.analysis.calculator.VolatilityCalculator;
import com.finagent.analysis.model.BetaResult;
import com.finagent.analysis.model.DiversificationResult;
import com.finagent.analysis.model.HoldingWeight;
import com.finagent.analysis.model.MaxDrawdownResult;
import com.finagent.analysis.model.PricePoint;
import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.analysis.model.RiskMetrics;
import com.finagent.analysis.model.RiskScore;
import com.finagent.analysis.model.SharpeRatioResult;
import com.finagent.analysis.model.VolatilityResult;
import com.finagent.common.TickerNormalizer;
import com.finagent.config.FinAgentProperties;
import com.finagent.market.BenchmarkDataProvider;
import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator for the deterministic financial risk engine (Phase 6).
 *
 * <p>This service is intentionally a thin coordinator: all formulas live in the
 * pure {@code analysis/calculator} and {@code analysis} classes. This service is
 * responsible only for fetching validated historical data through the existing
 * ports, building the price/return series the calculators consume, aligning stock
 * and benchmark series by date for beta, and assembling the
 * {@link RiskAnalysisResult}. It never fabricates data.</p>
 */
@Service
@Slf4j
public class RiskAnalysisService {

    private final StockDataService stockDataService;
    private final BenchmarkDataProvider benchmarkDataProvider;
    private final FinAgentProperties properties;

    public RiskAnalysisService(StockDataService stockDataService,
                               BenchmarkDataProvider benchmarkDataProvider,
                               FinAgentProperties properties) {
        this.stockDataService = stockDataService;
        this.benchmarkDataProvider = benchmarkDataProvider;
        this.properties = properties;
    }

    /**
     * Full deterministic risk analysis for one stock.
     *
     * @param symbol    ticker symbol
     * @param from      start date (inclusive)
     * @param to        end date (inclusive)
     * @param benchmark optional benchmark symbol (null uses configured default)
     * @return the assembled risk analysis (each metric carries its own availability flag)
     */
    public RiskAnalysisResult analyzeStockRisk(String symbol, LocalDate from, LocalDate to, String benchmark) {
        String normalized = TickerNormalizer.normalize(symbol);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(90);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("'from' date must not be after 'to' date");
        }

        HistoricalSeries stockSeries = stockDataService.getHistory(normalized, effectiveFrom, effectiveTo);
        List<HistoricalBar> bars = stockSeries.bars();
        log.debug("Risk analysis for {}: {} bars over [{} .. {}]", normalized, bars.size(), effectiveFrom, effectiveTo);

        List<BigDecimal> closes = new ArrayList<>(bars.size());
        List<PricePoint> points = new ArrayList<>(bars.size());
        for (HistoricalBar bar : bars) {
            closes.add(bar.close());
            points.add(new PricePoint(bar.date(), bar.close()));
        }

        List<Double> returns = closes.size() >= 2 ? ReturnCalculator.compute(closes) : List.of();

        int tradingDays = properties.getRisk().getTradingDaysPerYear();
        VolatilityResult volatility = returns.size() >= 2
                ? VolatilityCalculator.annualized(returns, tradingDays)
                : VolatilityResult.unavailable("Need at least 2 closing prices");
        MaxDrawdownResult maxDrawdown = MaxDrawdownCalculator.compute(points);
        SharpeRatioResult sharpe = SharpeRatioCalculator.compute(returns, tradingDays,
                properties.getRisk().getRiskFreeRate());
        BetaResult beta = computeBeta(returns, bars, benchmark, effectiveFrom, effectiveTo);

        RiskScore score = RiskScoringService.score(new RiskMetrics(
                valueOrNull(volatility), valueOrNull(maxDrawdown), valueOrNull(beta),
                valueOrNull(sharpe), null));

        return new RiskAnalysisResult(normalized, effectiveFrom, effectiveTo, bars.size(),
                volatility, maxDrawdown, beta, sharpe, score);
    }

    /** Deterministic portfolio diversification analysis from explicit holdings. */
    public DiversificationResult analyzeDiversification(List<HoldingWeight> holdings) {
        return PortfolioDiversificationAnalyzer.analyze(holdings);
    }

    private BetaResult computeBeta(List<Double> stockReturns, List<HistoricalBar> stockBars,
                                   String benchmark, LocalDate from, LocalDate to) {
        String benchmarkSymbol = (benchmark == null || benchmark.isBlank())
                ? properties.getRisk().getDefaultBenchmark()
                : TickerNormalizer.normalize(benchmark);
        if (benchmarkSymbol == null || benchmarkSymbol.isBlank()) {
            return BetaResult.unavailable("No benchmark symbol configured", null);
        }
        try {
            HistoricalSeries benchSeries = benchmarkDataProvider.getBenchmarkHistory(benchmarkSymbol, from, to);
            Map<LocalDate, BigDecimal> benchByDate = new LinkedHashMap<>();
            for (HistoricalBar bar : benchSeries.bars()) {
                benchByDate.put(bar.date(), bar.close());
            }
            List<BigDecimal> pairedStock = new ArrayList<>();
            List<BigDecimal> pairedBench = new ArrayList<>();
            for (HistoricalBar bar : stockBars) {
                BigDecimal benchClose = benchByDate.get(bar.date());
                if (benchClose != null) {
                    pairedStock.add(bar.close());
                    pairedBench.add(benchClose);
                }
            }
            if (pairedStock.size() < properties.getRisk().getMinObservations()) {
                return BetaResult.unavailable(
                        "Insufficient aligned observations: need at least "
                                + properties.getRisk().getMinObservations() + ", got " + pairedStock.size(),
                        benchmarkSymbol);
            }
            List<Double> benchReturns = ReturnCalculator.compute(pairedBench);
            return BetaCalculator.compute(stockReturns, benchReturns,
                    properties.getRisk().getMinObservations(), benchmarkSymbol);
        } catch (RuntimeException ex) {
            return BetaResult.unavailable("Benchmark unavailable: " + ex.getMessage(), benchmarkSymbol);
        }
    }

    private static Double valueOrNull(VolatilityResult r) {
        return r.available() ? r.annualizedVolatility() : null;
    }

    private static Double valueOrNull(MaxDrawdownResult r) {
        return r.available() ? r.maxDrawdown() : null;
    }

    private static Double valueOrNull(BetaResult r) {
        return r.available() ? r.beta() : null;
    }

    private static Double valueOrNull(SharpeRatioResult r) {
        return r.available() ? r.sharpeRatio() : null;
    }
}