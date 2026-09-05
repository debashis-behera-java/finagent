package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import com.finagent.model.Role;
import com.finagent.model.User;
import com.finagent.repository.UserRepository;
import com.finagent.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 16: AuthService rules — normalization, validation, hashing, generic
 * login failures, USER-only registration. No Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private AuditService auditService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(users, new JwtService(new FinAgentProperties()),
                new FinAgentProperties(), auditService);
    }

    @Test
    void registerHashesPasswordAndAssignsUserRole() {
        when(users.existsByEmail("analyst@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        AuthService.AuthResult result = authService.register("  Analyst@Example.COM ", "Secret123");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo("analyst@example.com");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getPasswordHash()).isNotEqualTo("Secret123");
        assertThat(new BCryptPasswordEncoder().matches("Secret123", user.getPasswordHash())).isTrue();
        assertThat(result.token()).isNotBlank();
        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    void registerRejectsDuplicateEmailCaseInsensitively() {
        when(users.existsByEmail("analyst@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("ANALYST@example.com", "Secret123"))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("An account with this email already exists");
    }

    @Test
    void registerRejectsInvalidEmail() {
        assertThatThrownBy(() -> authService.register("not-an-email", "Secret123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authService.register("   ", "Secret123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerRejectsWeakPassword() {
        for (String weak : new String[]{"short1", "no-digits-here", "12345678", "", null}) {
            assertThatThrownBy(() -> authService.register("a@example.com", weak))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> authService.register("a@example.com", "a1".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginSucceedsWithValidCredentials() {
        User user = new User("analyst@example.com",
                new BCryptPasswordEncoder().encode("Secret123"), Role.USER);
        user.setId(UUID.randomUUID());
        when(users.findByEmail("analyst@example.com")).thenReturn(Optional.of(user));

        AuthService.AuthResult result = authService.login("analyst@example.com", "Secret123");

        assertThat(result.token()).isNotBlank();
        assertThat(result.email()).isEqualTo("analyst@example.com");
    }

    @Test
    void loginFailuresAreGenericAndRevealNothing() {
        // Unknown email…
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        // …wrong password…
        User user = new User("analyst@example.com",
                new BCryptPasswordEncoder().encode("Secret123"), Role.USER);
        when(users.findByEmail("analyst@example.com")).thenReturn(Optional.of(user));

        BadCredentialsException unknown = null;
        BadCredentialsException wrong = null;
        try {
            authService.login("ghost@example.com", "Secret123");
        } catch (BadCredentialsException ex) {
            unknown = ex;
        }
        try {
            authService.login("analyst@example.com", "Wrong9999");
        } catch (BadCredentialsException ex) {
            wrong = ex;
        }
        assertThat(unknown).isNotNull();
        assertThat(wrong).isNotNull();
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage())
                .isEqualTo("Invalid email or password");
    }
}
