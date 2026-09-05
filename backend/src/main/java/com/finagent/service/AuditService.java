package com.finagent.service;

import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.repository.AuditEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Phase 17: minimal immutable audit trail for security-sensitive events.
 *
 * <p>Each event commits in its OWN transaction (programmatic
 * {@code REQUIRES_NEW} template): an audit write must neither join (and risk
 * poisoning) the business transaction nor be lost when the business
 * transaction rolls back. The commit itself happens INSIDE the guarded block,
 * so even a rollback failure is swallowed to a warn log — a failing audit
 * store can never break the request it observes. No retention scheduler here:
 * retention/archival is a documented future operational concern, and nothing
 * is kept in application memory.</p>
 *
 * <p>Lenient wiring ({@code ObjectProvider}): with no database (DB-less dev
 * profile) the repository/transaction manager are absent and audit becomes a
 * no-op instead of breaking the context.</p>
 *
 * <p>Privacy: failed-login attribution uses a SHA-256 hash of the normalized
 * email (abuse detection without storing the address); successes reference the
 * user id. Passwords, tokens, keys, and request bodies are never recorded —
 * the API takes only typed safe values, never raw credentials.</p>
 */
@Service
@Slf4j
public class AuditService {

    static final int MAX_METADATA = 2000;

    private final ObjectProvider<AuditEventRepository> repositories;
    private final ObjectProvider<PlatformTransactionManager> transactionManagers;

    public AuditService(ObjectProvider<AuditEventRepository> repositories,
                        ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this.repositories = repositories;
        this.transactionManagers = transactionManagers;
    }

    /** Record an event with a safe user reference (null when anonymous/unknown). */
    public void record(AuditEventType type, UUID userId, AuditEvent.Result result, String metadata) {
        AuditEventRepository events = repositories.getIfAvailable();
        PlatformTransactionManager transactions = transactionManagers.getIfAvailable();
        if (events == null || transactions == null) {
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            template.execute(status -> {
                events.save(new AuditEvent(type, userId, result, truncate(metadata)));
                events.flush();
                return null;
            });
        } catch (RuntimeException ex) {
            // The audit store must never break the request it observes.
            log.warn("Audit write failed for {}: {}", type, ex.getMessage());
        }
    }

    /** SHA-256 of the normalized email for failed-login attribution (no PII stored). */
    public static String emailHash(String normalizedEmail) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (normalizedEmail == null ? "" : normalizedEmail).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Tiny metadata JSON with one id field (UUIDs need no escaping). */
    public static String idMetadata(String key, UUID id) {
        return id == null ? "{}" : "{\"" + key + "\":\"" + id + "\"}";
    }

    /** Tiny metadata JSON with one pre-validated safe string value. */
    public static String valueMetadata(String key, String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        if (safe.length() > 500) {
            safe = safe.substring(0, 500);
        }
        return "{\"" + key + "\":\"" + safe + "\"}";
    }

    static String truncate(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "{}";
        }
        return metadata.length() > MAX_METADATA ? metadata.substring(0, MAX_METADATA) : metadata;
    }
}
