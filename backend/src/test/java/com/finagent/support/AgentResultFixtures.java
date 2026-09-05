package com.finagent.support;

import com.finagent.agent.model.AgentResult;
import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.agent.model.GuardResult;
import com.finagent.market.dto.Quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared canned {@link AgentResult}s for orchestration tests (mocked agent, no LLM). */
public final class AgentResultFixtures {

    private AgentResultFixtures() {
    }

    public static AgentResult canned(String... tickers) {
        List<FactEntry> entries = new ArrayList<>();
        for (String ticker : tickers) {
            Quote quote = new Quote(ticker, new BigDecimal("150.25"), new BigDecimal("2.50"),
                    new BigDecimal("1.69"), Instant.now());
            entries.add(new FactEntry(ticker, quote, null, 5, null, null, 2,
                    List.of(ticker + " headline one", ticker + " headline two"),
                    null, null, List.of()));
        }
        FactBundle bundle = new FactBundle("query", List.of(tickers), Instant.now(), entries);
        String prose = "Steady outlook. This is educational research, not financial advice.";
        return new AgentResult(bundle, prose, new GuardResult(true, List.of(), prose), Instant.now());
    }
}
