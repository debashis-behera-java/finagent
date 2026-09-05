package com.finagent.news.sentiment;

import com.finagent.config.FinAgentProperties;
import com.finagent.news.dto.NewsArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Deterministic lexicon analyzer tests with known examples (offline, no LLM).
 */
class LexiconSentimentAnalyzerTest {

    private LexiconSentimentAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new LexiconSentimentAnalyzer(new FinAgentProperties());
    }

    private static NewsArticle article(String title, String description) {
        return new NewsArticle("AAPL", title, "https://example.com/x", "Wire",
                description, null, Instant.parse("2026-08-27T16:30:00Z"));
    }

    @Test
    void strongPositiveArticle() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Record profits beat expectations on strong growth", "Quarterly update.")));

        assertThat(result.label()).isEqualTo(SentimentLabel.POSITIVE);
        assertThat(result.score()).isCloseTo(1.0, within(1e-9));
        assertThat(result.confidence()).isCloseTo(1.0, within(1e-9));
        assertThat(result.analyzedCount()).isOne();
        assertThat(result.positiveCount()).isOne();
        assertThat(result.articles().get(0).positiveHits()).isEqualTo(5);
        assertThat(result.articles().get(0).negativeHits()).isZero();
    }

    @Test
    void strongNegativeArticle() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Downgrade warnings as losses and decline miss hopes", "Update.")));

        assertThat(result.label()).isEqualTo(SentimentLabel.NEGATIVE);
        assertThat(result.score()).isCloseTo(-1.0, within(1e-9));
        assertThat(result.negativeCount()).isOne();
    }

    @Test
    void neutralArticleWithoutSignalWords() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Company announces new product line", "Update scheduled for Tuesday.")));

        assertThat(result.label()).isEqualTo(SentimentLabel.NEUTRAL);
        assertThat(result.score()).isCloseTo(0.0, within(1e-9));
        assertThat(result.confidence()).isCloseTo(0.0, within(1e-9));
        assertThat(result.neutralCount()).isOne();
    }

    @Test
    void mixedArticleScoresZeroWithPartialConfidence() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Strong gains offset by weak miss", "Update.")));

        assertThat(result.label()).isEqualTo(SentimentLabel.NEUTRAL);
        assertThat(result.score()).isCloseTo(0.0, within(1e-9));
        assertThat(result.confidence()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void emptyArticleIsSkippedNotNeutral() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(article("  ", null)));

        assertThat(result.label()).isEqualTo(SentimentLabel.UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.INSUFFICIENT_CONTENT);
        assertThat(result.score()).isNull();
        assertThat(result.analyzedCount()).isZero();
        assertThat(result.unavailableCount()).isOne();
    }

    @Test
    void missingTitleFallsBackToDescription() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article(null, "Profits surge on record demand")));

        assertThat(result.label()).isEqualTo(SentimentLabel.POSITIVE);
        assertThat(result.analyzedCount()).isOne();
        assertThat(result.articles().get(0).articleTitle()).isEqualTo("(untitled)");
    }

    @Test
    void missingDescriptionUsesTitleOnly() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Downgrade amid fraud fears", null)));

        assertThat(result.label()).isEqualTo(SentimentLabel.NEGATIVE);
        assertThat(result.analyzedCount()).isOne();
    }

    @Test
    void repeatedTermsAccumulateEvidence() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Growth growth growth", "Update.")));

        assertThat(result.label()).isEqualTo(SentimentLabel.POSITIVE);
        assertThat(result.score()).isCloseTo(1.0, within(1e-9));
        assertThat(result.confidence()).isCloseTo(0.6, within(1e-9));
        assertThat(result.articles().get(0).positiveHits()).isEqualTo(3);
    }

    @Test
    void matchingIgnoresCaseAndPunctuation() {
        SentimentResult lower = analyzer.analyze("AAPL", List.of(
                article("record profits soar", "update")));
        SentimentResult upper = analyzer.analyze("AAPL", List.of(
                article("RECORD PROFITS SOAR!!!", "UPDATE...")));

        assertThat(upper.score()).isCloseTo(lower.score(), within(1e-9));
        assertThat(upper.label()).isEqualTo(SentimentLabel.POSITIVE);
    }

    @Test
    void noRecognizedWordsIsLowConfidenceNeutral() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Committee meets about quarterly calendar", "Minutes published.")));

        assertThat(result.label()).isEqualTo(SentimentLabel.NEUTRAL);
        assertThat(result.confidence()).isCloseTo(0.0, within(1e-9));
        assertThat(result.isAvailable()).isTrue();
    }

    @Test
    void aggregatesAcrossArticlesWithSimpleMean() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Record profits soar", "Update."),
                article("Downgrade amid losses", "Update."),
                article("Company announces product line", "Update.")));

        assertThat(result.articleCount()).isEqualTo(3);
        assertThat(result.analyzedCount()).isEqualTo(3);
        assertThat(result.positiveCount()).isOne();
        assertThat(result.negativeCount()).isOne();
        assertThat(result.neutralCount()).isOne();
        assertThat(result.score()).isCloseTo(0.0, within(1e-9));
        assertThat(result.label()).isEqualTo(SentimentLabel.NEUTRAL);
        assertThat(result.methodology()).isNotBlank();
    }

    @Test
    void nullAndEmptyInputIsNoArticles() {
        assertThat(analyzer.analyze("AAPL", null).unavailableReason())
                .isEqualTo(UnavailableReason.NO_ARTICLES);
        assertThat(analyzer.analyze("AAPL", List.of()).unavailableReason())
                .isEqualTo(UnavailableReason.NO_ARTICLES);
        assertThat(analyzer.analyze("AAPL", null).label()).isEqualTo(SentimentLabel.UNAVAILABLE);
    }

    @Test
    void blankSymbolRejected() {
        assertThatThrownBy(() -> analyzer.analyze("  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoresAlwaysBounded() {
        SentimentResult result = analyzer.analyze("AAPL", List.of(
                article("Record profits beat strong growth rally soar breakthrough robust", "Gain gain gain."),
                article("Downgrade losses decline miss lawsuit fraud bankruptcy crash slump", "Fall fall fall.")));

        assertThat(result.score()).isBetween(-1.0, 1.0);
        assertThat(result.confidence()).isBetween(0.0, 1.0);
        for (ArticleSentiment article : result.articles()) {
            assertThat(article.score()).isBetween(-1.0, 1.0);
        }
    }
}
