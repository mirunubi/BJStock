-- Phase 1.1 hardening tests.
-- All rows are created inside this transaction and rolled back.

BEGIN;

CREATE TEMP TABLE phase11_results (
    test_name TEXT PRIMARY KEY,
    status TEXT NOT NULL
);

INSERT INTO bjstock.instruments (id, market, symbol, name, currency)
VALUES (9101, 'KRX', 'H00001', 'Hardening Test Instrument', 'KRW');

INSERT INTO bjstock.strategies (id, strategy_code, strategy_name)
VALUES (9101, 'HARDEN_STRAT', 'Hardening Strategy');

INSERT INTO bjstock.strategy_versions (
    id, strategy_id, version_no, buy_threshold, sell_threshold, status
)
VALUES (9101, 9101, 1, 70, 40, 'ACTIVE');

INSERT INTO bjstock.strategy_runs (
    id, run_name, strategy_version_id, run_type, start_date, end_date, initial_cash, status
)
VALUES (9101, 'Hardening Run', 9101, 'PAPER', DATE '2026-10-01', DATE '2027-09-30', 100000000, 'READY');

INSERT INTO bjstock.stock_evaluations (
    id, strategy_run_id, instrument_id, evaluation_date,
    quant_score, final_score, quant_decision, final_decision
)
VALUES (9101, 9101, 9101, DATE '2026-10-01', 80, 80, 'BUY', 'BUY');

-- Invalid value_type
DO $$
BEGIN
    INSERT INTO bjstock.factor_definitions (
        factor_code, factor_name, category, value_type, higher_is_better
    )
    VALUES ('INVALID_TYPE_FACTOR', 'Invalid Type', 'OTHER', 'INVALID', TRUE);
    INSERT INTO phase11_results VALUES ('invalid value_type', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase11_results VALUES ('invalid value_type', 'PASS');
END $$;

-- Valid value_types
DO $$
BEGIN
    INSERT INTO bjstock.factor_definitions (
        factor_code, factor_name, category, value_type, higher_is_better
    )
    VALUES
        ('VT_NUMBER', 'Number Factor', 'OTHER', 'NUMBER', TRUE),
        ('VT_PERCENT', 'Percent Factor', 'OTHER', 'PERCENT', TRUE),
        ('VT_RATIO', 'Ratio Factor', 'OTHER', 'RATIO', TRUE),
        ('VT_CURRENCY', 'Currency Factor', 'OTHER', 'CURRENCY', TRUE),
        ('VT_COUNT', 'Count Factor', 'OTHER', 'COUNT', TRUE);
    INSERT INTO phase11_results VALUES ('valid value_types', 'PASS');
EXCEPTION
    WHEN others THEN
        INSERT INTO phase11_results VALUES ('valid value_types', 'FAIL');
END $$;

INSERT INTO bjstock.ai_advice_requests (
    id, evaluation_id, provider, model, prompt_version, request_payload, requested_at
)
VALUES (
    9101, 9101, 'TEST', 'test-model-a', 'p1', '{"n":1}', CURRENT_TIMESTAMP
);

INSERT INTO bjstock.ai_advice_results (
    request_id, recommendation, confidence, summary, used_in_decision
)
VALUES (9101, 'BUY', 0.5, 'First result', FALSE);

-- Duplicate AI result on same request
DO $$
BEGIN
    INSERT INTO bjstock.ai_advice_results (
        request_id, recommendation, confidence, summary, used_in_decision
    )
    VALUES (9101, 'HOLD', 0.4, 'Second result same request', FALSE);
    INSERT INTO phase11_results VALUES ('duplicate AI result', 'FAIL');
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO phase11_results VALUES ('duplicate AI result', 'PASS');
END $$;

-- Multiple requests on the same evaluation, each with one result
DO $$
BEGIN
    INSERT INTO bjstock.ai_advice_requests (
        id, evaluation_id, provider, model, prompt_version, request_payload, requested_at
    )
    VALUES (
        9102, 9101, 'TEST', 'test-model-b', 'p2', '{"n":2}', CURRENT_TIMESTAMP
    );

    INSERT INTO bjstock.ai_advice_results (
        request_id, recommendation, confidence, summary, used_in_decision
    )
    VALUES (9102, 'HOLD', 0.6, 'Second request result', FALSE);

    IF (
        SELECT COUNT(*) FROM bjstock.ai_advice_requests WHERE evaluation_id = 9101
    ) = 2
    AND (
        SELECT COUNT(*) FROM bjstock.ai_advice_results WHERE request_id IN (9101, 9102)
    ) = 2
    THEN
        INSERT INTO phase11_results VALUES ('multiple AI requests', 'PASS');
    ELSE
        INSERT INTO phase11_results VALUES ('multiple AI requests', 'FAIL');
    END IF;
EXCEPTION
    WHEN others THEN
        INSERT INTO phase11_results VALUES ('multiple AI requests', 'FAIL');
END $$;

SELECT test_name, status
FROM phase11_results
ORDER BY test_name;

SELECT
    COUNT(*) FILTER (WHERE status = 'PASS') AS pass_count,
    COUNT(*) FILTER (WHERE status = 'FAIL') AS fail_count,
    COUNT(*) AS total_count
FROM phase11_results;

ROLLBACK;
