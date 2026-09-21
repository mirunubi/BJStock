-- Phase 1 normal domain-flow test.
-- Creates a full graph, verifies FK connectivity, then rolls back.
-- No seed rows remain.

BEGIN;

INSERT INTO bjstock.instruments (
    id, market, symbol, name, sector, industry, currency, is_active, listed_date
)
VALUES (
    8001, 'KRX', 'N00001', 'Normal Flow Instrument', 'TEST', 'TEST', 'KRW', TRUE, DATE '2000-01-01'
);

INSERT INTO bjstock.market_daily_bars (
    instrument_id, trade_date, open_price, high_price, low_price, close_price,
    volume, trading_value, source, collected_at
)
VALUES (
    8001, DATE '2026-10-01', 10000, 10200, 9900, 10100,
    1000000, 10100000000, 'TEST', TIMESTAMPTZ '2026-10-01 06:30:00+00'
);

INSERT INTO bjstock.factor_definitions (
    id, factor_code, factor_name, category, description, value_type, higher_is_better, is_active
)
VALUES (
    8001, 'NF_MOMENTUM', 'Normal Flow Momentum', 'MOMENTUM', 'Flow test factor', 'SCORE', TRUE, TRUE
);

INSERT INTO bjstock.factor_values (
    instrument_id, factor_id, evaluation_date, raw_value, normalized_score, source, calculation_version
)
VALUES (8001, 8001, DATE '2026-10-01', 12.5, 80, 'TEST', 'v1');

INSERT INTO bjstock.strategies (id, strategy_code, strategy_name, description, is_active)
VALUES (8001, 'NF_VALUE_MOMENTUM', 'Normal Flow Value Momentum', 'Flow test strategy', TRUE);

INSERT INTO bjstock.strategy_versions (
    id, strategy_id, version_no, description, buy_threshold, sell_threshold,
    valid_from, valid_to, status
)
VALUES (
    8001, 8001, 1, 'V1', 70, 40,
    DATE '2026-10-01', DATE '2027-09-30', 'ACTIVE'
);

INSERT INTO bjstock.strategy_factor_weights (
    strategy_version_id, factor_id, weight, min_score, max_score, enabled
)
VALUES (8001, 8001, 1.000000, 0, 100, TRUE);

INSERT INTO bjstock.strategy_runs (
    id, run_name, strategy_version_id, run_type, start_date, end_date,
    initial_cash, status, started_at
)
VALUES (
    8001, 'Run A VALUE_MOMENTUM V1', 8001, 'PAPER',
    DATE '2026-10-01', DATE '2027-09-30',
    100000000, 'RUNNING', TIMESTAMPTZ '2026-10-01 00:00:00+00'
);

INSERT INTO bjstock.stock_evaluations (
    id, strategy_run_id, instrument_id, evaluation_date,
    quant_score, ai_score, final_score, quant_decision, final_decision
)
VALUES (8001, 8001, 8001, DATE '2026-10-01', 80, 75, 80, 'BUY', 'BUY');

INSERT INTO bjstock.stock_evaluation_details (
    evaluation_id, factor_id, raw_value, factor_score, weight, weighted_score
)
VALUES (8001, 8001, 12.5, 80, 1.000000, 80);

INSERT INTO bjstock.orders (
    id, client_order_id, strategy_run_id, instrument_id, evaluation_id,
    side, order_type, requested_price, quantity, status, executed_at
)
VALUES (
    8001, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    8001, 8001, 8001, 'BUY', 'LIMIT', 10100, 10, 'VIRTUAL_FILLED',
    TIMESTAMPTZ '2026-10-01 06:40:00+00'
);

INSERT INTO bjstock.executions (
    order_id, execution_price, quantity, commission, tax, slippage, executed_at
)
VALUES (8001, 10100, 10, 0, 0, 0, TIMESTAMPTZ '2026-10-01 06:40:00+00');

INSERT INTO bjstock.positions (
    strategy_run_id, instrument_id, quantity, average_price, realized_profit
)
VALUES (8001, 8001, 10, 10100, 0);

INSERT INTO bjstock.portfolio_daily_snapshots (
    strategy_run_id, snapshot_date,
    cash, market_value, total_asset,
    daily_profit, daily_return, cumulative_profit, cumulative_return, drawdown
)
VALUES (
    8001, DATE '2026-10-01',
    898990.0000, 101000, 999990,
    -10, -0.00000010, -10, -0.00000010, 0
);

INSERT INTO bjstock.ai_advice_requests (
    id, evaluation_id, provider, model, prompt_version, request_payload, requested_at
)
VALUES (
    8001, 8001, 'TEST', 'test-model', 'p1',
    '{"instrument":"N00001"}',
    TIMESTAMPTZ '2026-10-01 06:35:00+00'
);

INSERT INTO bjstock.ai_advice_results (
    request_id, recommendation, confidence, summary, reasoning_summary, risk_notes,
    raw_response, used_in_decision
)
VALUES (
    8001, 'BUY', 0.7000, 'Positive momentum', 'Score above buy threshold', 'Test only',
    '{"recommendation":"BUY"}', FALSE
);

SELECT
    (SELECT COUNT(*) FROM bjstock.instruments WHERE id = 8001) AS instrument,
    (SELECT COUNT(*) FROM bjstock.factor_definitions WHERE id = 8001) AS factor,
    (SELECT COUNT(*) FROM bjstock.strategies WHERE id = 8001) AS strategy,
    (SELECT COUNT(*) FROM bjstock.strategy_versions WHERE id = 8001) AS version,
    (SELECT COUNT(*) FROM bjstock.strategy_factor_weights WHERE strategy_version_id = 8001) AS weight,
    (SELECT COUNT(*) FROM bjstock.strategy_runs WHERE id = 8001) AS run,
    (SELECT COUNT(*) FROM bjstock.stock_evaluations WHERE id = 8001) AS evaluation,
    (SELECT COUNT(*) FROM bjstock.stock_evaluation_details WHERE evaluation_id = 8001) AS evaluation_detail,
    (SELECT COUNT(*) FROM bjstock.orders WHERE id = 8001) AS order_row,
    (SELECT COUNT(*) FROM bjstock.executions WHERE order_id = 8001) AS execution,
    (SELECT COUNT(*) FROM bjstock.positions WHERE strategy_run_id = 8001) AS position,
    (SELECT COUNT(*) FROM bjstock.portfolio_daily_snapshots WHERE strategy_run_id = 8001) AS snapshot,
    (SELECT COUNT(*) FROM bjstock.ai_advice_requests WHERE id = 8001) AS ai_request,
    (SELECT COUNT(*) FROM bjstock.ai_advice_results WHERE request_id = 8001) AS ai_result;

SELECT
    i.symbol,
    s.strategy_code,
    sv.version_no,
    sr.run_name,
    se.final_decision,
    o.side,
    e.quantity AS filled_quantity,
    p.quantity AS position_quantity,
    snap.total_asset,
    ar.recommendation,
    ar.used_in_decision
FROM bjstock.instruments i
JOIN bjstock.stock_evaluations se ON se.instrument_id = i.id
JOIN bjstock.strategy_runs sr ON sr.id = se.strategy_run_id
JOIN bjstock.strategy_versions sv ON sv.id = sr.strategy_version_id
JOIN bjstock.strategies s ON s.id = sv.strategy_id
JOIN bjstock.orders o ON o.evaluation_id = se.id
JOIN bjstock.executions e ON e.order_id = o.id
JOIN bjstock.positions p ON p.strategy_run_id = sr.id AND p.instrument_id = i.id
JOIN bjstock.portfolio_daily_snapshots snap ON snap.strategy_run_id = sr.id
JOIN bjstock.ai_advice_requests aq ON aq.evaluation_id = se.id
JOIN bjstock.ai_advice_results ar ON ar.request_id = aq.id
WHERE i.id = 8001;

ROLLBACK;
