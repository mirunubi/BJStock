# Paper Trading Policy Snapshot

Phase 6.1 persists an **immutable** paper-trading policy for each strategy run so past runs remain reproducible after code defaults change.

## Purpose

Code defaults (`PaperTradingPolicy.DEFAULT`) are only a **template** used when creating a new run.

Live paper trading always reads:

```text
strategy_run_id → paper_trading_policies
```

Silent fallback to a global default is forbidden. Missing snapshot → `MISSING_TRADING_POLICY`.

## 1 Run = 1 Policy

```text
strategy_run
     │
     └── paper_trading_policy   (UNIQUE strategy_run_id)
```

A second snapshot for the same run fails (`DuplicateTradingPolicyException`).

## v1 Baseline (SIMULATION ASSUMPTION)

| Field | Value |
| --- | --- |
| policy_version | `v1` |
| buy_allocation_rate | 10% (`0.10`) |
| commission_rate | `0.00015` |
| sell_tax_rate | `0.0020` |
| slippage_bps | `0` |
| execution_price_policy | `NEXT_TRADING_DAY_OPEN` |
| additional_buy_policy | `DISALLOW` |
| sell_policy | `FULL_POSITION` |
| short_selling_allowed | `false` |

Commission and tax are **SIMULATION ASSUMPTION** fixtures. They are not documented as current Korean legal tax rates or a specific broker fee schedule.

Room stores rates as `Long * WEIGHT_FACTOR` (`1_000_000`). PostgreSQL stores the same rates as `NUMERIC(8,6)`.

## Immutability

Once a snapshot exists, BJStock does not expose an update API.

Policy change means:

```text
create a new Strategy Run (new snapshot from the then-current template)
```

READY / RUNNING / PAUSED / COMPLETED / CANCELLED runs never mutate policy rows.

## Reproducibility

If Run A was created with allocation 10% / commission 0.00015 / tax 0.0020, and the code template later becomes 20% / 0 / 0:

- Run A continues to trade using its snapshot.
- Run B created after the template change snapshots the new defaults.

## Run Initialization

READY transition (one Room transaction):

```text
Strategy Run insert
  → Paper Trading Policy Snapshot
  → INITIAL_DEPOSIT
  → READY
```

## Execution Date Semantics

No separate `execution_date` column.

```text
executed_at = simulated market execution date as UTC midnight Instant
created_at  = BJStock record creation timestamp (wall clock)
```

Example:

```text
Market Date 2026-09-22
executed_at 2026-09-22T00:00:00Z
```

Analytics must not treat `created_at` as the trading day.
