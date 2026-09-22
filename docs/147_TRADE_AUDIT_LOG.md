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

## ORDER_SKIPPED

Examples:

- BUY with open position → `POSITION_ALREADY_OPEN`
- SELL with no position → `NO_POSITION_TO_SELL`
