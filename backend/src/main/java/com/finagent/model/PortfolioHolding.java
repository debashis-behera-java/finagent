package com.finagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One position inside a portfolio: ticker, quantity, cost basis and sector
 * (sector feeds the concentration / diversification analysis).
 */
@Entity
@Table(name = "portfolio_holdings",
        uniqueConstraints = @UniqueConstraint(name = "uq_portfolio_ticker",
                columnNames = {"portfolio_id", "ticker"}),
        indexes = {
                @Index(name = "idx_portfolio_holdings_portfolio_id", columnList = "portfolio_id"),
                @Index(name = "idx_portfolio_holdings_ticker", columnList = "ticker")
        })
@Getter
@Setter
@NoArgsConstructor
public class PortfolioHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @NotBlank
    @Size(max = 12)
    @Column(nullable = false, length = 12)
    private String ticker;

    @NotNull
    @DecimalMin(value = "0.0", message = "quantity must be zero or positive")
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @NotNull
    @DecimalMin(value = "0.0", message = "cost basis must be zero or positive")
    @Column(name = "cost_basis", nullable = false, precision = 18, scale = 4)
    private BigDecimal costBasis;

    @Size(max = 100)
    @Column(length = 100)
    private String sector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
