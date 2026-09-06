package com.finagent.market.adapter;

import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubMarketDataProviderTest {

    private final StubMarketDataProvider provider = new StubMarketDataProvider();

    @Test
    void quoteIsDeterministicForSameSymbol() {
        Quote first = provider.getQuote("AAPL");
        Quote second = provider.getQuote("AAPL");

        assertThat(first.price()).isEqualTo(second.price());
        assertThat(first.symbol()).isEqualTo("AAPL");
    }

    @Test
    void quotePriceIsWithinSaneBounds() {
        Quote quote = provider.getQuote("MSFT");
        assertThat(quote.price()).isGreaterThan(BigDecimal.ZERO);
        assertThat(quote.price()).isLessThan(new BigDecimal("10000"));
        assertThat(quote.changePercent()).isNotNull();
    }

    @Test
    void historyBarsAreSortedAscendingAndWithinRange() {
        LocalDate from = LocalDate.of(2026, 8, 3);
        LocalDate to = LocalDate.of(2026, 8, 21);

        HistoricalSeries series = provider.getHistory("AAPL", from, to);

        assertThat(series.symbol()).isEqualTo("AAPL");
        assertThat(series.bars()).isNotEmpty();
        assertThat(series.bars()).allSatisfy(bar ->
                assertThat(bar.date()).isBetween(from, to));
        List<LocalDate> dates = series.bars().stream().map(b -> b.date()).toList();
        assertThat(dates).isSorted();
        // weekends are skipped
        assertThat(dates).noneSatisfy(d ->
                assertThat(d.getDayOfWeek()).isIn(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY));
    }

    @Test
    void fundamentalsArePresent() {
        Fundamentals fundamentals = provider.getFundamentals("AAPL");

        assertThat(fundamentals.symbol()).isEqualTo("AAPL");
        assertThat(fundamentals.companyName()).contains("AAPL");
        assertThat(fundamentals.sector()).isNotBlank();
        assertThat(fundamentals.peRatio()).isNotNull();
    }

    @Test
    void unknownSymbolThrows() {
        assertThatThrownBy(() -> provider.getQuote("UNKNOWN"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    void failSymbolThrowsProviderException() {
        assertThatThrownBy(() -> provider.getQuote("FAIL"))
                .isInstanceOf(ProviderException.class)
                .isNotInstanceOf(UnknownSymbolException.class);
    }
}
