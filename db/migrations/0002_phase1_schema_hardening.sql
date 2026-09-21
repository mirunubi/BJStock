-- BJStock Phase 1.1 — schema hardening from approved human decisions.
-- Does not modify 0001_initial_business_schema.sql.
--
-- H-01: factor_definitions.value_type closed TEXT + CHECK
-- H-02: strategy_runs.run_type stays PAPER-only (no change in this file)
-- H-03: ai_advice_results.request_id UNIQUE (1 request → 1 result)

SET search_path TO bjstock, public;

ALTER TABLE bjstock.factor_definitions
    DROP CONSTRAINT ck_factor_definitions_value_type_not_empty;

ALTER TABLE bjstock.factor_definitions
    ADD CONSTRAINT ck_factor_definitions_value_type
        CHECK (value_type IN (
            'NUMBER',
            'PERCENT',
            'RATIO',
            'CURRENCY',
            'COUNT'
        ));

COMMENT ON COLUMN bjstock.factor_definitions.value_type IS
    'Closed TEXT + CHECK: NUMBER, PERCENT, RATIO, CURRENCY, COUNT. Not ENUM. No string/date/JSON types.';

ALTER TABLE bjstock.ai_advice_results
    ADD CONSTRAINT uq_ai_advice_results_request_id UNIQUE (request_id);

DROP INDEX IF EXISTS bjstock.idx_ai_advice_results_request_id;

COMMENT ON TABLE bjstock.ai_advice_results IS
    'AI advisory output. One result per request. Additional opinions use additional requests.';
COMMENT ON COLUMN bjstock.ai_advice_results.request_id IS
    '1:1 with ai_advice_requests. Multiple advisors = multiple request rows.';
