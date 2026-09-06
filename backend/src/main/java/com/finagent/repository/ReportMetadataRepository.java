package com.finagent.repository;

import com.finagent.model.ReportMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReportMetadataRepository extends JpaRepository<ReportMetadata, UUID> {

    Optional<ReportMetadata> findByResearchRequestId(UUID requestId);
}
