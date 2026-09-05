package com.finagent.repository;

import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.model.Role;
import com.finagent.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16: authentication persistence against real PostgreSQL 16.
 *
 * <p>Verifies Flyway V3 creates the users table, the UNIQUE email constraint,
 * hash/role/timestamp round-trips, and Hibernate validation of the new entity.
 * Requires a Docker/container runtime; runs under
 * {@code mvn verify -Ppostgres-integration}.</p>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class PostgresAuthIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("finagent");

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Test
    void flywayAppliedUsersMigration() {
        List<String> applied = Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .map(Object::toString)
                .toList();

        assertThat(applied).contains("3", "4");
        assertThat(jdbc.queryForObject(
                "select count(*) from users", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events", Long.class)).isZero();
    }

    @Test
    void userRoundTripWithTimestamps() {
        User saved = users.saveAndFlush(new User("pg@example.com",
                new BCryptPasswordEncoder().encode("Secret123"), Role.USER));
        assertThat(saved.getId()).isNotNull();

        em.clear();
        User found = users.findByEmail("pg@example.com").orElseThrow();

        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(new BCryptPasswordEncoder().matches("Secret123", found.getPasswordHash())).isTrue();
    }

    @Test
    void uniqueEmailEnforcedByPostgres() {
        users.saveAndFlush(new User("unique@example.com",
                new BCryptPasswordEncoder().encode("Secret123"), Role.USER));

        assertThatThrownBy(() -> users.saveAndFlush(new User("unique@example.com",
                new BCryptPasswordEncoder().encode("Other456"), Role.ADMIN)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roleCheckConstraintRejectsUnknownRoles() {
        Integer violations = jdbc.queryForObject(
                "select count(*) from users where role not in ('USER','ADMIN')", Integer.class);
        assertThat(violations).isZero();
    }

    @Test
    void auditEventRoundTrip() {
        AuditEvent saved = auditEvents.saveAndFlush(new AuditEvent(
                AuditEventType.RESEARCH_SUBMITTED, null, AuditEvent.Result.SUCCESS,
                "{\"researchId\":\"00000000-0000-0000-0000-000000000000\"}"));
        assertThat(saved.getId()).isNotNull();

        em.clear();
        AuditEvent found = auditEvents.findById(saved.getId()).orElseThrow();

        assertThat(found.getOccurredAt()).isNotNull();
        assertThat(found.getEventType()).isEqualTo(AuditEventType.RESEARCH_SUBMITTED);
        assertThat(found.getUserId()).isNull();
        assertThat(found.getResult()).isEqualTo(AuditEvent.Result.SUCCESS);
    }

    @Test
    void auditResultCheckConstraintHolds() {
        Integer violations = jdbc.queryForObject(
                "select count(*) from audit_events where result not in ('SUCCESS','FAILURE','DENIED')",
                Integer.class);
        assertThat(violations).isZero();
    }
}
