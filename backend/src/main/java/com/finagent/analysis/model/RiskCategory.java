package com.finagent.analysis.model;

/**
 * Transparent risk category derived from the composite risk score.
 * Thresholds (documented in docs/risk-methodology.md):
 * [0, 33.33) LOW; [33.33, 66.66] MODERATE; (66.66, 100] HIGH.
 */
public enum RiskCategory {
    LOW,
    MODERATE,
    HIGH
}