-- Phase 1 negative constraint tests.
-- All rows are created inside this transaction and rolled back.

BEGIN;

CREATE TEMP TABLE phase1_negative_results (
    test_name TEXT PRIMARY KEY,
    status TEXT NOT NULL
);

INSERT INTO bjstock.instruments (id, market, symbol, name, currency)
VALUES (9001, 'KRX', 'T00001', 'Constraint Test Instrument', 'KRW');

INSERT INTO bjstock.factor_definitions (
    id, factor_code, factor_name, category, value_type, higher_is_better
)
VALUES (9001, 'TEST_FACTOR', 'Test Factor', 'OTHER', 'SCORE', TRUE);

INSERT INTO bjstock.strategies (id, strategy_code, strategy_name)
VALUES (9001, 'TEST_STRAT', 'Test Strategy');

INSERT INTO bjstock.strategy_versions (
    id, strategy_id, version_no, buy_threshold, sell_threshold, status
)
VALUES (9001, 9001, 1, 70, 40, 'ACTIVE');

INSERT INTO bjstock.strategy_runs (
    id, run_name, strategy_version_id, run_type, start_date, end_date, initial_cash, status
)
VALUES (9001, 'Constraint Run', 9001, 'PAPER', DATE '2026-10-01', DATE '2027-09-30', 100000000, 'READY');

INSERT INTO bjstock.stock_evaluations (
    id, strategy_run_id, instrument_id, evaluation_date,
    quant_score, final_score, quant_decision, final_decision
)
VALUES (9001, 9001, 9001, DATE '2026-10-01', 80, 80, 'BUY', 'BUY');

INSERT INTO bjstock.orders (
    id, client_order_id, strategy_run_id, instrument_id, evaluation_id,
    side, order_type, requested_price, quantity, status
)
VALUES (
    9001, '11111111-1111-1111-1111-111111111111',
    9001, 9001, 9001, 'BUY', 'LIMIT', 1000, 10, 'CREATED'
);

INSERT INTO bjstock.portfolio_daily_snapshots (
    id, strategy_run_id, snapshot_date,
    cash, market_value, total_asset,
    daily_profit, daily_return, cumulative_profit, cumulative_return, drawdown
)
VALUES (
    9001, 9001, DATE '2026-10-01',
    100000000, 0, 100000000,
    0, 0, 0, 0, 0
);

-- 1. same market + symbol
DO $$
BEGIN
    INSERT INTO bjstock.instruments (market, symbol, name, currency)
    VALUES ('KRX', 'T00001', 'Duplicate Instrument', 'KRW');
    INSERT INTO phase1_negative_results VALUES ('duplicate market+symbol', 'FAIL');
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO phase1_negative_results VALUES ('duplicate market+symbol', 'PASS');
END $$;

-- 2. same instrument + trade_date
INSERT INTO bjstock.market_daily_bars (
    instrument_id, trade_date, open_price, high_price, low_price, close_price,
    volume, source, collected_at
)
VALUES (9001, DATE '2026-10-01', 10, 11, 9, 10.5, 1000, 'TEST', CURRENT_TIMESTAMP);

DO $$
BEGIN
    INSERT INTO bjstock.market_daily_bars (
        instrument_id, trade_date, open_price, high_price, low_price, close_price,
        volume, source, collected_at
    )
    VALUES (9001, DATE '2026-10-01', 10, 11, 9, 10.5, 1000, 'TEST', CURRENT_TIMESTAMP);
    INSERT INTO phase1_negative_results VALUES ('duplicate instrument+trade_date', 'FAIL');
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO phase1_negative_results VALUES ('duplicate instrument+trade_date', 'PASS');
END $$;

-- 3. normalized_score > 100
DO $$
BEGIN
    INSERT INTO bjstock.factor_values (
        instrument_id, factor_id, evaluation_date, raw_value, normalized_score,
        source, calculation_version
    )
    VALUES (9001, 9001, DATE '2026-10-01', 1, 100.0001, 'TEST', 'v1');
    INSERT INTO phase1_negative_results VALUES ('normalized_score > 100', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase1_negative_results VALUES ('normalized_score > 100', 'PASS');
END $$;

-- 4. weight < 0
DO $$
BEGIN
    INSERT INTO bjstock.strategy_factor_weights (
        strategy_version_id, factor_id, weight, enabled
    )
    VALUES (9001, 9001, -0.1, TRUE);
    INSERT INTO phase1_negative_results VALUES ('weight < 0', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase1_negative_results VALUES ('weight < 0', 'PASS');
END $$;

-- 5. same strategy/version
DO $$
BEGIN
    INSERT INTO bjstock.strategy_versions (
        strategy_id, version_no, buy_threshold, sell_threshold, status
    )
    VALUES (9001, 1, 70, 40, 'DRAFT');
    INSERT INTO phase1_negative_results VALUES ('duplicate strategy/version', 'FAIL');
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO phase1_negative_results VALUES ('duplicate strategy/version', 'PASS');
END $$;

-- 6. initial_cash <= 0
DO $$
BEGIN
    INSERT INTO bjstock.strategy_runs (
        run_name, strategy_version_id, run_type, start_date, initial_cash, status
    )
    VALUES ('Zero Cash', 9001, 'PAPER', DATE '2026-10-01', 0, 'DRAFT');
    INSERT INTO phase1_negative_results VALUES ('initial_cash <= 0', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase1_negative_results VALUES ('initial_cash <= 0', 'PASS');
END $$;

-- 7. end_date < start_date
DO $$
BEGIN
    INSERT INTO bjstock.strategy_runs (
        run_name, strategy_version_id, run_type, start_date, end_date, initial_cash, status
    )
    VALUES ('Bad Dates', 9001, 'PAPER', DATE '2026-10-01', DATE '2026-09-01', 100000000, 'DRAFT');
    INSERT INTO phase1_negative_results VALUES ('end_date < start_date', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase1_negative_results VALUES ('end_date < start_date', 'PASS');
END $$;

-- 8. duplicate evaluation
DO $$
BEGIN
    INSERT INTO bjstock.stock_evaluations (
        strategy_run_id, instrument_id, evaluation_date,
        quant_score, final_score, quant_decision, final_decision
    )
    VALUES (9001, 9001, DATE '2026-10-01', 50, 50, 'HOLD', 'HOLD');
    INSERT INTO phase1_negative_results VALUES ('duplicate evaluation', 'FAIL');
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO phase1_negative_results VALUES ('duplicate evaluation', 'PASS');
END $$;

-- 9. side other than BUY/SELL
DO $$
BEGIN
    INSERT INTO bjstock.orders (
        client_order_id, strategy_run_id, instrument_id,
        side, order_type, quantity, status
    )
    VALUES (
        '22222222-2222-2222-2222-222222222222',
        9001, 9001, 'HOLD', 'MARKET', 1, 'CREATED'
    );
    INSERT INTO phase1_negative_results VALUES ('side other than BUY/SELL', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase1_negative_results VALUES ('side other than BUY/SELL', 'PASS');
END $$;

-- 10. execution quantity <= 0
DO $$
BEGIN
    INSERT INTO bjstock.executions (
        order_id, execution_price, quantity, executed_at
    )
    VALUES (9001, 1000, 0, CURRENT_TIMESTAMP);
    INSERT INTO phase1_negative_results VALUES ('execution quantity <= 0', 'FAIL');
EXCEPTION
    WHEN check_violation THEN
        INSERT INTO phase1_negative_results VALUES ('execution quantity <= 0', 'PASS');
END $$;

-- 11. duplicate snapshot date
DO $$
BEGIN
    INSERT INTO bjstock.portfolio_daily_snapshots (
        strategy_run_id, snapshot_date,
        cash, market_value, total_asset,
        daily_profit, daily_return, cumulative_profit, cumulative_return, drawdown
    )
    VALUES (
        9001, DATE '2026-10-01',
        100000000, 0, 100000000,
        0, 0, 0, 0, 0
    );
    INSERT INTO phase1_negative_results VALUES ('duplicate snapshot date', 'FAIL');
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO phase1_negative_results VALUES ('duplicate snapshot date', 'PASS');
END $$;

SELECT test_name, status
FROM phase1_negative_results
ORDER BY test_name;

SELECT
    COUNT(*) FILTER (WHERE status = 'PASS') AS pass_count,
    COUNT(*) FILTER (WHERE status = 'FAIL') AS fail_count,
    COUNT(*) AS total_count
FROM phase1_negative_results;

ROLLBACK;
