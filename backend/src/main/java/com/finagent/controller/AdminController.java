package com.finagent.controller;

import com.finagent.auth.RefreshTokenCleanupService;
import com.finagent.dto.response.AuthDtos.SessionStatsDto;
import com.finagent.dto.response.AuthDtos.UserDto;
import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.repository.UserRepository;
import com.finagent.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 16: minimal operational endpoint (ADMIN only — enforced by the
 * security chain). Exists so the USER/ADMIN boundary is real and testable,
 * not decorative.
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Tag(name = "Admin", description = "Operational endpoints (ADMIN only, Phase 16)")
public class AdminController {

    private final UserRepository users;
    private final AuditService audit;
    private final RefreshTokenCleanupService sessions;

    public AdminController(UserRepository users, AuditService audit,
                           RefreshTokenCleanupService sessions) {
        this.users = users;
        this.audit = audit;
        this.sessions = sessions;
    }

    @GetMapping("/users")
    @Operation(summary = "List registered users (ADMIN only, no password material)")
    public List<UserDto> listUsers(@AuthenticationPrincipal UserDetails principal) {
        UUID adminId = null;
        try {
            adminId = principal == null ? null : UUID.fromString(principal.getUsername());
        } catch (IllegalArgumentException ex) {
            adminId = null;
        }
        audit.record(AuditEventType.ADMIN_USERS_LISTED, adminId, AuditEvent.Result.SUCCESS, "{}");
        return users.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getEmail(), u.getRole(), u.getCreatedAt()))
                .toList();
    }

    @GetMapping("/auth/sessions")
    @Operation(summary = "Aggregate refresh-session statistics (ADMIN only; counts only, "
            + "never token values or hashes)")
    public SessionStatsDto sessionStats(@AuthenticationPrincipal UserDetails principal) {
        UUID adminId = null;
        try {
            adminId = principal == null ? null : UUID.fromString(principal.getUsername());
        } catch (IllegalArgumentException ex) {
            adminId = null;
        }
        audit.record(AuditEventType.ADMIN_SESSION_STATS_VIEWED, adminId,
                AuditEvent.Result.SUCCESS, "{}");
        RefreshTokenCleanupService.SessionStats stats = sessions.stats();
        return new SessionStatsDto(stats.active(), stats.revoked(), stats.expired(),
                stats.families(), stats.lastCleanupAt(), stats.lastCleanupDeleted());
    }
}
