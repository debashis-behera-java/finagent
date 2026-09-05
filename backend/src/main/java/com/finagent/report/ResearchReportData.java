package com.finagent.report;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Presentation snapshot for one PDF research report (Phase 10).
 *
 * <p>Built once from the persisted {@code ResearchStatusDto} by
 * {@link com.finagent.service.ResearchReportService} — the generator renders
 * exactly this data and performs no research, no AI calls, and no I/O.
 * Decimal figures are carried as plain strings (lossless from the JSON
 * snapshot); {@code null} means "unavailable" and must be rendered as the
 * word "Unavailable", never as 0 or N/A.</p>
 */
public record ResearchReportData(
        UUID researchId,
        String requestText,
        Instant generatedAt,
        Instant completedAt,
        List<String> tickers,
        String executiveSummary,
        String interpretation,
        String newsSummary,
        String disclaimer,
        int guardRedactions,
        List<Entry> entries) {

    public ResearchReportData {
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * Per-ticker facts. Any String field may be {@code null} (= unavailable).
     * Counts are always present (0 when nothing was retrieved).
     */
    public record Entry(
            String symbol,
            String companyName,
            String price,
            String change,
            String changePercent,
            String sector,
            String marketCap,
            String peRatio,
            int historyBars,
            int newsCount,
            List<String> headlines,
            String riskScore,
            String riskCategory,
            String volatility,
            String maxDrawdown,
            String beta,
            String sharpe,
            String sentimentStatus,
            String sentimentLabel,
            String sentimentScore,
            String sentimentConfidence,
            String sentimentReason,
            int articleCount,
            int analyzedCount,
            int positiveCount,
            int neutralCount,
            int negativeCount,
            String methodology,
            List<String> gaps) {

        public Entry {
            headlines = headlines == null ? List.of() : List.copyOf(headlines);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        public boolean hasMarketData() {
            return price != null;
        }

        public boolean hasFundamentals() {
            return companyName != null || sector != null || marketCap != null || peRatio != null;
        }

        public boolean hasRisk() {
            return riskScore != null || volatility != null || maxDrawdown != null
                    || beta != null || sharpe != null;
        }

        public boolean hasSentiment() {
            return sentimentStatus != null && !"not-analyzed".equals(sentimentStatus);
        }

        public boolean sentimentAvailable() {
            return "available".equals(sentimentStatus);
        }
    }
}
