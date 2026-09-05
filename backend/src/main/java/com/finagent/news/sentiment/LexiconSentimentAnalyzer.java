package com.finagent.news.sentiment;

import com.finagent.config.FinAgentProperties;
import com.finagent.news.dto.NewsArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Transparent keyword-baseline sentiment analyzer (Phase 9).
 *
 * <p><b>Methodology (lexicon-v1):</b> each article's title + description is lowercased,
 * split on non-letters, and matched whole-word against a small financial lexicon.
 * Every occurrence counts (repeated terms add evidence — a deliberate, documented
 * choice). Article score = {@code (pos − neg) / (pos + neg)} ∈ [−1, +1]; articles
 * with zero hits score 0.0 (NEUTRAL) with 0.0 confidence. Aggregate score is the
 * simple mean of analyzed article scores; aggregate confidence is the mean of
 * article confidences. Labels: score ≥ +0.20 POSITIVE, ≤ −0.20 NEGATIVE, else
 * NEUTRAL (thresholds configurable, defaults justified by keeping weak single-hit
 * signals out of the directional buckets).</p>
 *
 * <p><b>Known limitations:</b> no negation handling ("not strong" reads positive),
 * no stemming (inflections must be listed explicitly), whole-word match only
 * ("losses" ≠ "loss" unless listed), no sarcasm/irony detection, and the lexicon
 * is small by design — this is a baseline, not professional financial NLP.</p>
 */
@Component
@Slf4j
public class LexiconSentimentAnalyzer implements SentimentAnalyzer {

    public static final String METHODOLOGY =
            "lexicon-v1: per-article (pos-neg)/(pos+neg) over title+description, "
            + "simple-mean aggregation, labels at +/-0.20, confidence min(1,hits/5)";

    private final Set<String> positiveTerms;
    private final Set<String> negativeTerms;
    private final double positiveThreshold;
    private final double negativeThreshold;
    private final double confidenceHits;

    public LexiconSentimentAnalyzer(FinAgentProperties properties) {
        FinAgentProperties.Sentiment config = properties.getSentiment();
        this.positiveTerms = lowerCased(config.getPositiveTerms());
        this.negativeTerms = lowerCased(config.getNegativeTerms());
        this.positiveThreshold = config.getPositiveThreshold();
        this.negativeThreshold = config.getNegativeThreshold();
        this.confidenceHits = config.getConfidenceHits();
        log.info("LexiconSentimentAnalyzer: {} positive / {} negative terms, thresholds [{}, {}]",
                positiveTerms.size(), negativeTerms.size(), negativeThreshold, positiveThreshold);
    }

    @Override
    public SentimentResult analyze(String symbol, List<NewsArticle> articles) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Sentiment symbol must not be blank");
        }
        String normalized = symbol.strip().toUpperCase(Locale.ROOT);
        if (articles == null || articles.isEmpty()) {
            return SentimentResult.unavailable(normalized, UnavailableReason.NO_ARTICLES, METHODOLOGY);
        }
        List<ArticleSentiment> analyzed = new ArrayList<>();
        int skipped = 0;
        for (NewsArticle article : articles) {
            ArticleSentiment sentiment = scoreArticle(article);
            if (sentiment == null) {
                skipped++;
            } else {
                analyzed.add(sentiment);
            }
        }
        if (analyzed.isEmpty()) {
            return new SentimentResult(normalized, SentimentLabel.UNAVAILABLE, null, 0.0,
                    articles.size(), 0, skipped, 0, 0, 0,
                    UnavailableReason.INSUFFICIENT_CONTENT, METHODOLOGY, List.of());
        }
        double meanScore = analyzed.stream().mapToDouble(ArticleSentiment::score).average().orElse(0.0);
        double meanConfidence = analyzed.stream()
                .mapToDouble(a -> confidence(a.positiveHits() + a.negativeHits()))
                .average().orElse(0.0);
        int positive = 0;
        int neutral = 0;
        int negative = 0;
        for (ArticleSentiment sentiment : analyzed) {
            switch (sentiment.label()) {
                case POSITIVE -> positive++;
                case NEGATIVE -> negative++;
                default -> neutral++;
            }
        }
        SentimentResult result = new SentimentResult(normalized, labelFor(meanScore), meanScore,
                meanConfidence, articles.size(), analyzed.size(), skipped,
                positive, neutral, negative, null, METHODOLOGY, analyzed);
        log.debug("Sentiment for {}: {} ({}) over {}/{} articles",
                normalized, result.label(), result.score(), analyzed.size(), articles.size());
        return result;
    }

    /** Scores one article; {@code null} when it has no usable text (skipped, not neutral). */
    private ArticleSentiment scoreArticle(NewsArticle article) {
        String title = article != null && article.title() != null ? article.title() : "";
        String description = article != null && article.description() != null ? article.description() : "";
        String text = (title + " " + description).strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        int positive = 0;
        int negative = 0;
        for (String token : text.split("[^a-z]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (positiveTerms.contains(token)) {
                positive++;
            }
            if (negativeTerms.contains(token)) {
                negative++;
            }
        }
        int hits = positive + negative;
        double score = hits == 0 ? 0.0 : (double) (positive - negative) / hits;
        return new ArticleSentiment(
                title.isBlank() ? "(untitled)" : title.strip(),
                article.url(),
                hits == 0 ? SentimentLabel.NEUTRAL : labelFor(score),
                score,
                positive,
                negative);
    }

    private SentimentLabel labelFor(double score) {
        if (score >= positiveThreshold) {
            return SentimentLabel.POSITIVE;
        }
        if (score <= negativeThreshold) {
            return SentimentLabel.NEGATIVE;
        }
        return SentimentLabel.NEUTRAL;
    }

    private double confidence(int hits) {
        return Math.min(1.0, hits / confidenceHits);
    }

    private static Set<String> lowerCased(List<String> terms) {
        Set<String> normalized = new HashSet<>();
        if (terms != null) {
            for (String term : terms) {
                if (term != null && !term.isBlank()) {
                    normalized.add(term.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return normalized;
    }
}
