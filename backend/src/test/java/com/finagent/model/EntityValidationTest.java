package com.finagent.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Jakarta Validation constraints on the JPA entities (no Spring context needed).
 */
class EntityValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private ResearchRequest validRequest() {
        ResearchRequest r = new ResearchRequest();
        r.setRequestText("Analyze Apple risk");
        r.getTickers().add("AAPL");
        return r;
    }

    private PortfolioHolding validHolding() {
        PortfolioHolding h = new PortfolioHolding();
        h.setTicker("AAPL");
        h.setQuantity(new BigDecimal("10"));
        h.setCostBasis(new BigDecimal("150.50"));
        return h;
    }

    @Test
    void requestWithBlankTextIsInvalid() {
        ResearchRequest r = validRequest();
        r.setRequestText("   ");

        Set<ConstraintViolation<ResearchRequest>> violations = VALIDATOR.validate(r);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("requestText"));
    }

    @Test
    void requestWithOversizedTickerIsInvalid() {
        ResearchRequest r = validRequest();
        r.getTickers().add("THIRTEENCHARS");

        Set<ConstraintViolation<ResearchRequest>> violations = VALIDATOR.validate(r);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("tickers"));
    }

    @Test
    void holdingWithNegativeQuantityIsInvalid() {
        PortfolioHolding h = validHolding();
        h.setQuantity(new BigDecimal("-1"));

        Set<ConstraintViolation<PortfolioHolding>> violations = VALIDATOR.validate(h);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("quantity"));
    }

    @Test
    void holdingWithBlankTickerIsInvalid() {
        PortfolioHolding h = validHolding();
        h.setTicker("");

        Set<ConstraintViolation<PortfolioHolding>> violations = VALIDATOR.validate(h);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("ticker"));
    }

    @Test
    void portfolioWithBlankNameIsInvalid() {
        Portfolio p = new Portfolio();
        p.setName("");

        Set<ConstraintViolation<Portfolio>> violations = VALIDATOR.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void toolExecutionRequiresNameAndStatus() {
        ResearchToolExecution t = new ResearchToolExecution();

        Set<ConstraintViolation<ResearchToolExecution>> violations = VALIDATOR.validate(t);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("toolName"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("status"));
    }

    @Test
    void validEntitiesPassValidation() {
        assertThat(VALIDATOR.validate(validRequest())).isEmpty();
        assertThat(VALIDATOR.validate(validHolding())).isEmpty();

        Portfolio p = new Portfolio();
        p.setName("Core");
        assertThat(VALIDATOR.validate(p)).isEmpty();

        ResearchToolExecution t = new ResearchToolExecution();
        t.setToolName("get_stock_price");
        t.setStatus(com.finagent.model.enums.ToolExecutionStatus.SUCCESS);
        assertThat(VALIDATOR.validate(t)).isEmpty();
    }
}
