package com.finagent.analysis.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single price observation with its date. Used as input to the pure
 * drawdown/price-series calculators so they stay decoupled from the
 * market-data DTOs.
 */
public record PricePoint(LocalDate date, BigDecimal price) {
}