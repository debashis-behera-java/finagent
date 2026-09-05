package com.finagent.controller;

import com.finagent.service.AsyncResearchRunner;
import com.finagent.service.ResearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code dev} profile runs without a database (Phase 1 tradeoff): the JPA
 * repositories do not exist there, so the research workflow beans must be absent
 * and the context must still boot. The endpoints come alive under the
 * {@code test}/{@code postgres} profiles.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ResearchDisabledWithoutDatabaseTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void researchBeansAreAbsentWithoutDatabase() {
        assertThat(context.getBeansOfType(ResearchController.class)).isEmpty();
        assertThat(context.getBeansOfType(ResearchOrchestrator.class)).isEmpty();
        assertThat(context.getBeansOfType(AsyncResearchRunner.class)).isEmpty();
    }
}
