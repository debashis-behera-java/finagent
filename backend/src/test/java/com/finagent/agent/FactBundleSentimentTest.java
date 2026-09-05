package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.market.dto.Quote;
import com.finagent.news.sentiment.LexiconSentimentAnalyzer;
import com.finagent.news.sentiment.SentimentLabel;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.news.sentiment.UnavailableReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactBundleSentimentTest {

    private final FactBundleBuilder builder = new FactBundleBuilder();

    private static SentimentResult sentiment() {
        return new SentimentResult("AAPL", SentimentLabel.POSITIVE, 0.6, 0.8,
                12, 12, 0, 7, 3, 2, null,
                LexiconSentimentAnalyzer.METHODOLOGY, List.of());
    }

    private static Quote quote() {
        return new Quote("AAPL", new BigDecimal("150.25"), new BigDecimal("2.50"),
                new BigDecimal("1.69"), Instant.now());
    }

    @Test
    void sentimentScoreAndCountsAreGrounded() {
        FactEntry entry = builder.buildEntry("AAPL", quote(), null, null, List.of(), null, sentiment());
        FactBundle bundle = builder.buildBundle("Analyze sentiment for $AAPL", List.of(entry));

        assertThat(bundle.groundedNumbers())
                .contains(new BigDecimal("0.6").stripTrailingZeros());
        assertThat(bundle.toPromptText()).contains("News sentiment: POSITIVE (score 0.6");
        assertThat(bundle.toPromptText()).contains("7 positive / 3 neutral / 2 negative of 12 analyzed");
    }

    @Test
    void missingSentimentRendersAsNotAnalyzed() {
        FactEntry entry = builder.buildEntry("AAPL", quote(), null, null, List.of(), null, null);
        FactBundle bundle = builder.buildBundle("What is the price of $AAPL?", List.of(entry));

        assertThat(bundle.toPromptText()).contains("News sentiment: not analyzed for this run");
    }

    @Test
    void unavailableSentimentRendersWithReason() {
        SentimentResult unavailable = SentimentResult.unavailable("AAPL",
                UnavailableReason.PROVIDER_ERROR, LexiconSentimentAnalyzer.METHODOLOGY);
        FactEntry entry = builder.buildEntry("AAPL", null, null, null, null, null, unavailable);

        assertThat(builder.buildBundle("Sentiment for $AAPL", List.of(entry)).toPromptText())
                .contains("News sentiment: unavailable (PROVIDER_ERROR)");
    }
}
