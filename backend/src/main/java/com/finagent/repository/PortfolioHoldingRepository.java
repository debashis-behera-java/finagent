package com.finagent.repository;

import com.finagent.model.PortfolioHolding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PortfolioHoldingRepository extends JpaRepository<PortfolioHolding, UUID> {

    List<PortfolioHolding> findByPortfolioId(UUID portfolioId);
}
