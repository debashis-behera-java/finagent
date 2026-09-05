package com.finagent.repository;

import com.finagent.model.Role;
import com.finagent.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16: user persistence on H2 — save/find, email lookup, uniqueness,
 * role and timestamps. Real-PostgreSQL coverage lives in PostgresAuthIT.
 */
@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private UserRepository users;

    private static User user(String email, Role role) {
        return new User(email, new BCryptPasswordEncoder().encode("Secret123"), role);
    }

    @Test
    void saveAndFindByEmail() {
        users.saveAndFlush(user("analyst@example.com", Role.USER));

        em.clear();
        User found = users.findByEmail("analyst@example.com").orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(new BCryptPasswordEncoder().matches("Secret123", found.getPasswordHash())).isTrue();
    }

    @Test
    void duplicateEmailIsRejected() {
        users.saveAndFlush(user("dupe@example.com", Role.USER));

        assertThatThrownBy(() -> users.saveAndFlush(user("dupe@example.com", Role.USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByEmail() {
        assertThat(users.existsByEmail("nobody@example.com")).isFalse();
        users.saveAndFlush(user("somebody@example.com", Role.USER));
        assertThat(users.existsByEmail("somebody@example.com")).isTrue();
    }

    @Test
    void adminRolePersists() {
        users.saveAndFlush(user("admin@example.com", Role.ADMIN));

        em.clear();
        assertThat(users.findByEmail("admin@example.com").orElseThrow().getRole())
                .isEqualTo(Role.ADMIN);
    }

    @Test
    void passwordHashIsNeverPlaintext() {
        User saved = users.saveAndFlush(user("hash@example.com", Role.USER));

        assertThat(saved.getPasswordHash()).isNotEqualTo("Secret123");
        assertThat(saved.getPasswordHash()).startsWith("$2");
    }
}
