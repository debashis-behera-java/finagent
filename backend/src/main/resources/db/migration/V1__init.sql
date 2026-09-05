-- ============================================================
-- FINAGENT - Phase 2: initial schema (PostgreSQL 16+)
-- Managed by Flyway. Never rely on Hibernate ddl-auto here.
-- ============================================================

-- ============ portfolios ============
CREATE TABLE portfolios (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_portfolios_name UNIQUE (name)
);

-- ============ portfolio_holdings ============
CREATE TABLE portfolio_holdings (
    id           UUID          PRIMARY KEY,
    portfolio_id UUID          NOT NULL,
    ticker       VARCHAR(12)   NOT NULL,
    quantity     NUMERIC(18,4) NOT NULL,
    cost_basis   NUMERIC(18,4) NOT NULL,
    sector       VARCHAR(100),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_holdings_portfolio FOREIGN KEY (portfolio_id)
        REFERENCES portfolios (id) ON DELETE CASCADE,
    CONSTRAINT uq_portfolio_ticker UNIQUE (portfolio_id, ticker),
    CONSTRAINT chk_holdings_quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT chk_holdings_cost_basis_non_negative CHECK (cost_basis >= 0)
);
CREATE INDEX idx_portfolio_holdings_portfolio_id ON portfolio_holdings (portfolio_id);
CREATE INDEX idx_portfolio_holdings_ticker ON portfolio_holdings (ticker);

-- ============ research_requests ============
CREATE TABLE research_requests (
    id           UUID        PRIMARY KEY,
    request_text TEXT        NOT NULL,
    portfolio_id UUID,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_requests_portfolio FOREIGN KEY (portfolio_id)
        REFERENCES portfolios (id),
    CONSTRAINT chk_requests_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_research_requests_status ON research_requests (status);
CREATE INDEX idx_research_requests_created_at ON research_requests (created_at);
CREATE INDEX idx_research_requests_portfolio_id ON research_requests (portfolio_id);

-- ============ research_request_tickers (element collection) ============
CREATE TABLE research_request_tickers (
    research_request_id UUID         NOT NULL,
    ticker              VARCHAR(12)  NOT NULL,
    PRIMARY KEY (research_request_id, ticker),
    CONSTRAINT fk_tickers_request FOREIGN KEY (research_request_id)
        REFERENCES research_requests (id) ON DELETE CASCADE
);
CREATE INDEX idx_request_tickers_ticker ON research_request_tickers (ticker);

-- ============ research_results (1:1 with research_requests) ============
CREATE TABLE research_results (
    id                UUID PRIMARY KEY,
    request_id        UUID NOT NULL,
    executive_summary TEXT,
    interpretation    TEXT,
    metrics_snapshot  TEXT,
    sentiment_summary TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_results_request FOREIGN KEY (request_id)
        REFERENCES research_requests (id) ON DELETE CASCADE,
    CONSTRAINT uq_results_request UNIQUE (request_id)
);
CREATE INDEX idx_research_results_created_at ON research_results (created_at);

-- ============ research_tool_executions (N:1, MCP audit trail) ============
CREATE TABLE research_tool_executions (
    id            UUID         PRIMARY KEY,
    request_id    UUID         NOT NULL,
    tool_name     VARCHAR(100) NOT NULL,
    input_hash    VARCHAR(64),
    status        VARCHAR(20)  NOT NULL,
    duration_ms   BIGINT,
    error_message TEXT,
    executed_at   TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_tool_exec_request FOREIGN KEY (request_id)
        REFERENCES research_requests (id) ON DELETE CASCADE,
    CONSTRAINT chk_tool_exec_status CHECK (status IN ('SUCCESS', 'FAILED'))
);
CREATE INDEX idx_tool_executions_request_id ON research_tool_executions (request_id);
CREATE INDEX idx_tool_executions_executed_at ON research_tool_executions (executed_at);
CREATE INDEX idx_tool_executions_request_tool ON research_tool_executions (request_id, tool_name);

-- ============ report_metadata (1:1 with research_requests) ============
CREATE TABLE report_metadata (
    id           UUID        PRIMARY KEY,
    request_id   UUID        NOT NULL,
    file_path    TEXT        NOT NULL,
    checksum     VARCHAR(64),
    page_count   INTEGER,
    generated_at TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_report_request FOREIGN KEY (request_id)
        REFERENCES research_requests (id) ON DELETE CASCADE,
    CONSTRAINT uq_report_request UNIQUE (request_id)
);
CREATE INDEX idx_report_metadata_generated_at ON report_metadata (generated_at);
