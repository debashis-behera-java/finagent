package com.finagent.repository;

import com.finagent.model.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {

    /** Loads a portfolio together with its holdings in one query (avoids N+1). */
    @Query("select p from Portfolio p left join fetch p.holdings where p.id = :id")
    Optional<Portfolio> findWithHoldingsById(@Param("id") UUID id);

    Optional<Portfolio> findByName(String name);
}
