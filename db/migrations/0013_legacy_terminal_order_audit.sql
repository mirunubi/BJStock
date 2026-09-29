-- BJStock Phase 11 / Gate 6.1 — legacy terminal-order audit reconciliation
-- Room parity: MIGRATION_10_11 (data-only). Appends the missing ORDER_REJECTED / ORDER_CANCELLED audit of
-- pre-Gate-6 terminal orders. Append-only and idempotent: orders and existing audit rows are never modified.
-- The reason is the only one the pre-Gate-6 writer could produce, recognised by the facts it persisted;
-- any other shape is LEGACY_REASON_UNKNOWN. market_date, decision_source and operation_id stay NULL.
-- See docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §20.6

SET search_path TO bjstock, public;

BEGIN;

INSERT INTO bjstock.trade_audit_logs (
    strategy_run_id, instrument_id, evaluation_id, order_id, event_type, reason_code, reason_text, event_key
)
SELECT o.strategy_run_id, o.instrument_id, o.evaluation_id, o.id, 'ORDER_REJECTED',
    CASE
        WHEN o.quantity = 0 AND o.side = 'BUY' THEN 'INSUFFICIENT_CASH'
        WHEN o.quantity = 0 AND o.side = 'SELL' THEN 'NO_POSITION_TO_SELL'
        ELSE 'LEGACY_REASON_UNKNOWN'
    END,
    'LEGACY_AUDIT_RESTORED: pre-Gate-6 REJECTED order had no audit',
    'order:' || o.id || ':rejected'
FROM bjstock.orders AS o
WHERE o.status = 'REJECTED'
  AND NOT EXISTS (
      SELECT 1 FROM bjstock.trade_audit_logs AS a
      WHERE a.event_key = 'order:' || o.id || ':rejected'
         OR (a.order_id = o.id AND a.event_type = 'ORDER_REJECTED')
  )
ORDER BY o.id;

INSERT INTO bjstock.trade_audit_logs (
    strategy_run_id, instrument_id, evaluation_id, order_id, event_type, reason_code, reason_text, event_key
)
SELECT o.strategy_run_id, o.instrument_id, o.evaluation_id, o.id, 'ORDER_CANCELLED',
    CASE WHEN o.cancelled_at IS NOT NULL THEN 'RUN_END_REACHED' ELSE 'LEGACY_REASON_UNKNOWN' END,
    'LEGACY_AUDIT_RESTORED: pre-Gate-6 CANCELLED order had no audit',
    'order:' || o.id || ':cancelled'
FROM bjstock.orders AS o
WHERE o.status = 'CANCELLED'
  AND NOT EXISTS (
      SELECT 1 FROM bjstock.trade_audit_logs AS a
      WHERE a.event_key = 'order:' || o.id || ':cancelled'
         OR (a.order_id = o.id AND a.event_type = 'ORDER_CANCELLED')
  )
ORDER BY o.id;

COMMIT;
