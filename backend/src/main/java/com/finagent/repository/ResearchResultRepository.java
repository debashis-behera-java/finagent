package com.finagent.repository;

import com.finagent.model.ResearchResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResearchResultRepository extends JpaRepository<ResearchResult, UUID> {

    Optional<ResearchResult> findByRequestId(UUID requestId);
}
