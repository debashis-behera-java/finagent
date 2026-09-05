package com.finagent.repository;

import com.finagent.model.ResearchRequest;
import com.finagent.model.enums.ResearchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ResearchRequestRepository extends JpaRepository<ResearchRequest, UUID> {

    Page<ResearchRequest> findByStatus(ResearchStatus status, Pageable pageable);

    Page<ResearchRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
