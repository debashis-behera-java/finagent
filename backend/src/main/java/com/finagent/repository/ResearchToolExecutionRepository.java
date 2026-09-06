package com.finagent.repository;

import com.finagent.model.ResearchToolExecution;
import com.finagent.model.enums.ToolExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResearchToolExecutionRepository extends JpaRepository<ResearchToolExecution, UUID> {

    List<ResearchToolExecution> findByResearchRequestIdOrderByExecutedAtAsc(UUID requestId);

    long countByResearchRequestIdAndStatus(UUID requestId, ToolExecutionStatus status);
}
