package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import com.finagent.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 4: housekeeping unit contract (no Spring context) — cutoff derivation
 * from the retention property, deleted-count reporting, run-statistics
 * recording, scheduler enable/disable fencing, and failure containment.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupServiceTest {

    @Mock
    private RefreshTokenRepository tokens;

    private static FinAgentProperties propertiesWith(Duration retention, boolean enabled) {
        FinAgentProperties properties = new FinAgentProperties();
        properties.getAuth().getRefreshToken().setRetention(retention);
        properties.getAuth().getRefreshToken().setCleanupEnabled(enabled);
        return properties;
    }

    @Test
    void runCleanupDerivesCutoffFromRetentionAndReportsCount() {
        FinAgentProperties properties = propertiesWith(Duration.ofDays(7), true);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(tokens, properties);
        when(tokens.deleteCleanupCandidates(any(), any())).thenReturn(4);

        Instant before = Instant.now();
        long deleted = service.runCleanup();

        assertThat(deleted).isEqualTo(4);
        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> revocation = ArgumentCaptor.forClass(Instant.class);
        verify(tokens).deleteCleanupCandidates(expiry.capture(), revocation.capture());
        // Cutoff ≈ now − 7d for both branches (single retention grace).
        assertThat(expiry.getValue()).isBefore(before.minus(Duration.ofDays(6)));
        assertThat(expiry.getValue()).isAfter(before.minus(Duration.ofDays(8)));
        assertThat(revocation.getValue()).isEqualTo(expiry.getValue());

        // Run statistics are recorded for the admin endpoint.
        RefreshTokenCleanupService.SessionStats stats = service.stats();
        assertThat(stats.lastCleanupDeleted()).isEqualTo(4);
        assertThat(stats.lastCleanupAt()).isNotNull()
                .isAfterOrEqualTo(before.minusSeconds(5))
                .isBeforeOrEqualTo(Instant.now().plusSeconds(5));
    }

    @Test
    void statsBeforeFirstRunCarryNullTimestampAndZeroCount() {
        RefreshTokenCleanupService service =
                new RefreshTokenCleanupService(tokens, propertiesWith(Duration.ofDays(7), true));
        when(tokens.countActive(any())).thenReturn(2L);
        when(tokens.countRevoked()).thenReturn(1L);
        when(tokens.countExpired(any())).thenReturn(0L);
        when(tokens.countFamilies()).thenReturn(2L);

        RefreshTokenCleanupService.SessionStats stats = service.stats();

        assertThat(stats.active()).isEqualTo(2L);
        assertThat(stats.revoked()).isEqualTo(1L);
        assertThat(stats.expired()).isZero();
        assertThat(stats.families()).isEqualTo(2L);
        assertThat(stats.lastCleanupAt()).isNull();
        assertThat(stats.lastCleanupDeleted()).isZero();
    }

    @Test
    void schedulerRunsCleanupWhenEnabled() {
        FinAgentProperties properties = propertiesWith(Duration.ofDays(7), true);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(tokens, properties);
        RefreshTokenCleanupScheduler scheduler = new RefreshTokenCleanupScheduler(service, properties);

        scheduler.runSafely();

        verify(tokens).deleteCleanupCandidates(any(), any());
    }

    @Test
    void schedulerSkipsEverythingWhenDisabled() {
        FinAgentProperties properties = propertiesWith(Duration.ofDays(7), false);
        RefreshTokenCleanupService service = new RefreshTokenCleanupService(tokens, properties);
        RefreshTokenCleanupScheduler scheduler = new RefreshTokenCleanupScheduler(service, properties);

        scheduler.runSafely();

        verify(tokens, never()).deleteCleanupCandidates(any(), any());
        assertThat(service.stats().lastCleanupAt()).isNull();
    }

    @Test
    void schedulerContainsDatabaseFailures() {
        FinAgentProperties properties = propertiesWith(Duration.ofDays(7), true);
        RefreshTokenCleanupService failing =
                org.mockito.Mockito.mock(RefreshTokenCleanupService.class);
        when(failing.runCleanup()).thenThrow(new RuntimeException("database is down"));
        RefreshTokenCleanupScheduler scheduler = new RefreshTokenCleanupScheduler(failing, properties);

        // Must not propagate: the app keeps serving, the next run retries.
        assertThatCode(scheduler::runSafely).doesNotThrowAnyException();
    }
}
