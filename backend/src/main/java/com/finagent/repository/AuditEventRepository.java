package com.finagent.repository;

import com.finagent.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Phase 17: audit-trail persistence. Read API only for now — retention and
 * archival are documented future operational concerns (no scheduler here).
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
}
