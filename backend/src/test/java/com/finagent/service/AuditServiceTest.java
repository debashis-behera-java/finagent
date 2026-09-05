package com.finagent.service;

import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17: audit-trail persistence on H2 — event round-trip, timestamps,
 * truncation, failure isolation (a poisoned write never throws), and the
 * no-secrets property. Real-PostgreSQL coverage lives in PostgresAuthIT.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditServiceTest {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditEventRepository events;

    @BeforeEach
    void clean() {
        events.deleteAll();
    }

    @Test
    void recordPersistsEventWithTimestamp() {
        UUID userId = UUID.randomUUID();
        audit.record(AuditEventType.AUTH_LOGIN_SUCCESS, userId,
                AuditEvent.Result.SUCCESS, AuditService.idMetadata("userId", userId));

        List<AuditEvent> all = events.findAll();
        assertThat(all).hasSize(1);
        AuditEvent event = all.get(0);
        assertThat(event.getId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.AUTH_LOGIN_SUCCESS);
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getResult()).isEqualTo(AuditEvent.Result.SUCCESS);
        assertThat(event.getMetadata()).contains(userId.toString());
    }

    @Test
    void anonymousEventsPersistWithNullUser() {
        audit.record(AuditEventType.AUTH_LOGIN_FAILURE, null,
                AuditEvent.Result.FAILURE,
                AuditService.valueMetadata("emailHash", AuditService.emailHash("a@example.com")));

        AuditEvent event = events.findAll().get(0);
        assertThat(event.getUserId()).isNull();
        // Hashed attribution only — the address itself is never stored.
        assertThat(event.getMetadata()).doesNotContain("a@example.com");
        assertThat(event.getMetadata()).contains(AuditService.emailHash("a@example.com"));
    }

    @Test
    void oversizedMetadataIsTruncated() {
        audit.record(AuditEventType.RESEARCH_FAILED, null,
                AuditEvent.Result.FAILURE, "x".repeat(5000));

        assertThat(events.findAll().get(0).getMetadata()).hasSize(AuditService.MAX_METADATA);
    }

    @Test
    void failingWriteNeverThrows() {
        // Null event type violates NOT NULL — must be swallowed, not propagated.
        audit.record(null, null, AuditEvent.Result.FAILURE, "{}");

        assertThat(events.count()).isZero();
    }

    @Test
    void storedEventsContainNoSecrets() {
        audit.record(AuditEventType.AUTH_REGISTER, UUID.randomUUID(),
                AuditEvent.Result.SUCCESS, "{}");

        String row = events.findAll().get(0).getMetadata()
                + events.findAll().get(0).getEventType();
        String lower = row.toLowerCase();
        assertThat(lower).doesNotContain("password", "token", "secret", "key", "bearer");
    }

    @Test
    void emailHashIsStableAndNonReversible() {
        assertThat(AuditService.emailHash("A@Example.com"))
                .isEqualTo(AuditService.emailHash("A@Example.com"))
                .hasSize(64)
                .doesNotContain("A@Example.com");
    }
}
