-- BJStock Phase 11 / Gate 6 — canonical financial event keys
-- Room parity: MIGRATION_9_10. Keys are derived from provable identity only;
-- amounts, balances, ids and timestamps are not modified. Ambiguous legacy rows abort the migration.
-- See docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §12

SET search_path TO bjstock, public;

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM bjstock.executions GROUP BY order_id HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'MIGRATION_0012_AMBIGUOUS_EXECUTION_KEY';
    END IF;
END $$;

ALTER TABLE bjstock.executions
    ADD COLUMN execution_key TEXT;

UPDATE bjstock.executions
SET execution_key = 'paper:order:' || order_id || ':fill:1';

ALTER TABLE bjstock.executions
    ALTER COLUMN execution_key SET NOT NULL;

ALTER TABLE bjstock.executions
    ADD CONSTRAINT uq_executions_execution_key UNIQUE (execution_key);

COMMENT ON COLUMN bjstock.executions.execution_key IS
    'Canonical fill identity. Paper: paper:order:<order_id>:fill:1 (one VIRTUAL_FILLED execution per order). Not UNIQUE(order_id): future broker partial fills use :fill:<n>.';

ALTER TABLE bjstock.cash_ledger
    ADD COLUMN event_key TEXT;

UPDATE bjstock.cash_ledger AS target
SET event_key = derived.event_key
FROM (
    SELECT c.id,
        CASE
            WHEN c.event_type = 'INITIAL_DEPOSIT' AND c.reference_type = 'STRATEGY_RUN'
                AND c.reference_id = c.strategy_run_id
                THEN 'run:' || c.strategy_run_id || ':initial-deposit'
            WHEN c.reference_type = 'EXECUTION' AND o.strategy_run_id = c.strategy_run_id THEN
                CASE
                    WHEN c.event_type = 'BUY' AND o.side = 'BUY'
                        THEN 'execution:' || c.reference_id || ':buy-principal'
                    WHEN c.event_type = 'COMMISSION' AND o.side = 'BUY'
                        THEN 'execution:' || c.reference_id || ':buy-commission'
                    WHEN c.event_type = 'SELL' AND o.side = 'SELL'
                        THEN 'execution:' || c.reference_id || ':sell-proceeds'
                    WHEN c.event_type = 'COMMISSION' AND o.side = 'SELL'
                        THEN 'execution:' || c.reference_id || ':sell-commission'
                    WHEN c.event_type = 'TAX' AND o.side = 'SELL'
                        THEN 'execution:' || c.reference_id || ':sell-tax'
                END
        END AS event_key
    FROM bjstock.cash_ledger AS c
    LEFT JOIN bjstock.executions AS e ON c.reference_type = 'EXECUTION' AND e.id = c.reference_id
    LEFT JOIN bjstock.orders AS o ON o.id = e.order_id
) AS derived
WHERE derived.id = target.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM bjstock.cash_ledger WHERE event_key IS NULL) THEN
        RAISE EXCEPTION 'MIGRATION_0012_UNRECOGNIZED_LEDGER_ROW';
    END IF;
END $$;

ALTER TABLE bjstock.cash_ledger
    ALTER COLUMN event_key SET NOT NULL;

ALTER TABLE bjstock.cash_ledger
    ADD CONSTRAINT uq_cash_ledger_event_key UNIQUE (event_key);

COMMENT ON COLUMN bjstock.cash_ledger.event_key IS
    'Canonical cash event identity: run:<run>:initial-deposit, execution:<id>:buy-principal|buy-commission|sell-proceeds|sell-commission|sell-tax.';

COMMIT;
