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

A live `EVALUATION_DECIDED` always carries one of the two. NULL appears **only** on legacy evidence whose source cannot be reconstructed, identified by `reason_code = LEGACY_AUDIT_RESTORED`; NULL is not a third decision source. There is no `LEGACY_UNKNOWN` value, and the PostgreSQL CHECK stays `NULL` / `SIGNAL_RULE` / `FACTOR_STRATEGY` (Gate 6.1, `docs/150` 20.6.4).

## Idempotency

Unique `event_key` (e.g. `evaluation:<id>:decision`, `order:<id>:created`, `order:<id>:rejected`, `order:<id>:cancelled`, `execution:<id>:filled`). Retries do not duplicate rows. A replayed key is accepted only when the stored row is the same logical event (run, event type, evaluation / order / execution ids); otherwise the write aborts with `INTERNAL_INVARIANT_VIOLATION` (`AUDIT_EVENT_KEY_CONFLICT`).

## Atomicity (Phase 11 / Gate 6)

Each required audit row commits in the same Room transaction as its business mutation (`docs/150` 20.5):

| Business mutation | Audit | Transaction |
| --- | --- | --- |
| evaluation + details | `RULE_TRIGGERED` (rule decisions) + `EVALUATION_DECIDED` | `StrategyEvaluationRepository.persistSnapshot` |
| order row | `ORDER_CREATED` | `ProcessEvaluationUseCase` |
| skip decision (no order row) | `ORDER_SKIPPED` | `ProcessEvaluationUseCase` (same transaction as the position read) |
| order → `REJECTED` | `ORDER_REJECTED` | `VirtualFillService.reject` |
| every pending order → `CANCELLED` at run end, then run → `COMPLETED` | one `ORDER_CANCELLED` per order | `ProcessPendingOrdersUseCase.finalizeRunEnd` (one transaction for the whole finalization, Gate 6.1) |
| order → `VIRTUAL_FILLED` + execution + ledger + position | `EXECUTION_FILLED` | `VirtualFillService.executeBuy` / `executeSell` |

If the audit insert fails, the business mutation rolls back.

## Legacy reconciliation

Rows committed before Gate 6 may lack their audit. On replay, the missing row is appended by its deterministic key without touching the business row:

| Replay path | Restored event |
| --- | --- |
| evaluation already exists (`ALREADY_EVALUATED`) | `EVALUATION_DECIDED` |
| order already exists (`ORDER_ALREADY_EXISTS`) | `ORDER_CREATED` |
| fill replay (`ALREADY_FILLED`) | `EXECUTION_FILLED` |

Restored rows carry `reason_code = LEGACY_AUDIT_RESTORED` and `operation_id = NULL`, because the current operation did not produce the event. `RULE_TRIGGERED` details (rule, metric, observed / threshold values) are not stored on `stock_evaluations`, so they are never fabricated.

A restored `EVALUATION_DECIDED` (Gate 6.1) carries `SIGNAL_RULE` when a `RULE_TRIGGERED` audit exists for the evaluation, `FACTOR_STRATEGY` when the run's strategy version has no enabled signal rules, and `decision_source = NULL` otherwise. The source is never guessed.

### Legacy `REJECTED` / `CANCELLED` orders (Gate 6.1)

Room `MIGRATION_10_11` / PostgreSQL `0013` append exactly one `ORDER_REJECTED` (`order:<id>:rejected`) or `ORDER_CANCELLED` (`order:<id>:cancelled`) for a terminal order that has none. `reason_code` is the historical reason only when the pre-Gate-6 writer's persisted facts prove it (REJECTED `quantity = 0`: BUY → `INSUFFICIENT_CASH`, SELL → `NO_POSITION_TO_SELL`; CANCELLED with `cancelled_at` → `RUN_END_REACHED`), otherwise `LEGACY_REASON_UNKNOWN`. `reason_text` starts with `LEGACY_AUDIT_RESTORED`; `market_date`, `decision_source`, `operation_id` are NULL. Orders with an existing audit are skipped, orders are never modified, and rerunning adds nothing (`docs/150` 20.6.3).

### Append-only historical rows

An existing audit row is never rewritten, including an earlier restored row whose `decision_source` is NULL; it stays as legacy evidence. Only rows written after Gate 6.1 use the rules above.

## Retention

Append-only. **Never** auto-deleted. Not subject to the 7-day API error rotation.

Target lifecycle (400-day hot, verified monthly archive, 5-year retention) is defined in `docs/150_OPERATIONAL_RELIABILITY_STANDARD.md` and is not active until verified archiving exists (D-149).

## Operation correlation

Room v8 adds nullable `operation_id` (soft reference to `forward_operations.id`). Existing rows stay NULL. Since Phase 11 / Gate 5, rows appended inside a Forward Test operation carry its id; an idempotent replay returns the existing row unchanged (`docs/150` 20.4.7).

## ORDER_SKIPPED

Examples:

- BUY with open position → `POSITION_ALREADY_OPEN`
- SELL with no position → `NO_POSITION_TO_SELL`

## ORDER_REJECTED / ORDER_CANCELLED reason codes

| Event | `reason_code` | When |
| --- | --- | --- |
| `ORDER_REJECTED` | `INSUFFICIENT_CASH` | BUY quantity at the next trading day open is 0 |
| `ORDER_REJECTED` | `NO_POSITION_TO_SELL` | SELL finds no position at fill time |
| `ORDER_CANCELLED` | `RUN_END_REACHED` | order still `PENDING_EXECUTION` when the run reaches its end date |
| `ORDER_REJECTED` / `ORDER_CANCELLED` | `LEGACY_REASON_UNKNOWN` | reconciled legacy order whose historical reason cannot be proven (Gate 6.1) |

Live rows carry run, instrument, evaluation, order, market date (fill date for rejection, run end date for cancellation), and the current `operation_id`. Reason codes are canonical tokens; free text is never used as business logic.

## Idempotency conflict vs exact replay (financial rows)

An exact replay of a fill or ledger event (same key, identical facts) is normal idempotent success. Conflicting facts under the same key abort with `EXECUTION_IDEMPOTENCY_CONFLICT` or `LEDGER_MISMATCH` (severity `FINANCIAL_INTEGRITY`); a ledger whose latest balance differs from the sum of its amounts also raises `LEDGER_MISMATCH` (`docs/150` 20.6.1).
