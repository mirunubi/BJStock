# Trade Audit Log

Human-readable decision / trade timeline.

## Roles

```text
orders / executions = Transaction Source of Truth
trade_audit_logs    = Human-readable reason / event timeline
```

Audit does **not** replace orders/executions.

## Event types

`RULE_TRIGGERED`, `EVALUATION_DECIDED`, `ORDER_CREATED`, `ORDER_SKIPPED`, `ORDER_REJECTED`, `ORDER_CANCELLED`, `EXECUTION_FILLED`

## Decision source

`SIGNAL_RULE` | `FACTOR_STRATEGY` (AI is never a trading authority)

## Idempotency

Unique `event_key` (e.g. `evaluation:<id>:decision`, `order:<id>:created`, `execution:<id>:filled`). Retries do not duplicate rows.

## Retention

Append-only. **Never** auto-deleted. Not subject to the 7-day API error rotation.

Target lifecycle (400-day hot, verified monthly archive, 5-year retention) is defined in `docs/150_OPERATIONAL_RELIABILITY_STANDARD.md` and is not active until verified archiving exists (D-149).

## Operation correlation

Room v8 adds nullable `operation_id` (soft reference to `forward_operations.id`). Existing rows stay NULL. Since Phase 11 / Gate 5, rows appended inside a Forward Test operation carry its id; an idempotent replay returns the existing row unchanged (`docs/150` 20.4.7).

## ORDER_SKIPPED

Examples:

- BUY with open position → `POSITION_ALREADY_OPEN`
- SELL with no position → `NO_POSITION_TO_SELL`
