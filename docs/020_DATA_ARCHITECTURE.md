# BJStock Data Architecture

Phase 1 defines the laboratory domain schema in Docker PostgreSQL.

This is a long-lived domain model for paper trading and later expansion. It is not a throwaway MVP schema.

## Runtime vs Laboratory

| Store | Technology | Role |
| --- | --- | --- |
| Android runtime DB | Room / SQLite | Future on-device app data |
| Development DB | Docker PostgreSQL 17 | Schema, PK/FK, constraint, index, and SQL experiments |

PostgreSQL is not the Android runtime database. The APK must not connect to Docker PostgreSQL.

Schema design therefore avoids PostgreSQL-only domain features: no ENUM, no ARRAY, no JSONB business columns, no generated columns, and no complex triggers.

## Numeric and Time Rules

- Financial prices and money: `NUMERIC`, never `FLOAT` / `REAL`
- Prices: `NUMERIC(18, 4)`
- Cash / notional / quantity money fields: `NUMERIC(20, 4)`
- Scores: `NUMERIC(7, 4)` in `0 .. 100`
- Weights / confidence: `NUMERIC(8, 6)` or `NUMERIC(6, 4)` in `0 .. 1`
- Returns / drawdown: `NUMERIC(12, 8)`
- Timestamps: `TIMESTAMPTZ`, stored in UTC
- Trade / evaluation / snapshot days: `DATE`
- Android display timezone: `Asia/Seoul`

## Status Values

Closed vocabularies use `TEXT + CHECK`, not PostgreSQL ENUM.

`factor_definitions.value_type` allows only:

```text
NUMBER
PERCENT
RATIO
CURRENCY
COUNT
```

String, date, and JSON factor types are not allowed. New types require a migration.

`strategy_runs.run_type` currently allows `PAPER` only. `LIVE` is not pre-declared.

## Forward Test Unit

`strategy_runs` is the independent execution unit.

Each run has:

- one strategy version
- a date range
- initial virtual cash
- its own evaluations, orders, positions, and snapshots

Two runs can share the same market period and compare different strategies.

Phase 0 candidate `trading_accounts` is not a table. The virtual book belongs to the run.

## Domain Map

```text
instruments
      │
      ├──────── market_daily_bars
      │
      └──────── factor_values
                     │
factor_definitions ──┘


strategies
      │
      ▼
strategy_versions
      │
      ├──────── strategy_factor_weights
      │                  │
      │                  └── factor_definitions
      │
      ▼
strategy_runs
      │
      ├──────── stock_evaluations
      │                 │
      │                 ▼
      │       stock_evaluation_details
      │
      ├──────── positions
      │
      ├──────── orders
      │             │
      │             ▼
      │         executions
      │
      └──────── portfolio_daily_snapshots


stock_evaluations
      │
      └──────── ai_advice_requests
                        │
                        ▼
                ai_advice_results
```

## Tables

### Market

- `instruments`
- `market_daily_bars`

### Factor

- `factor_definitions`
- `factor_values`

Store `raw_value` and `normalized_score` together when both exist.

### Strategy

- `strategies`
- `strategy_versions`
- `strategy_factor_weights`

A version used in a live forward test is treated as immutable. Do not overwrite historical settings.

Enabled weights should sum to `1.0` per version. This is checked by `db/scripts/verify_strategy_weights.sql`, not by a trigger.

### Forward Test

- `strategy_runs`

### Evaluation

- `stock_evaluations`
- `stock_evaluation_details`

One official evaluation per run / instrument / date.

Decision replay path:

```text
Evaluation → Evaluation Detail → Decision → Virtual Order
```

### Paper Trading Ledger

- `orders`
- `executions`
- `positions`

`orders` / `executions` are the source of truth.

`positions` is a current-state projection for fast lookup.

### Performance

- `portfolio_daily_snapshots`

Used later for cumulative return, MDD, volatility, monthly return, and benchmark comparison.

### AI Advisory

- `ai_advice_requests`
- `ai_advice_results`

Optional history. AI does not execute trades. Payloads are TEXT, never secrets.

Request and result are 1:1. One request has at most one result. Additional opinions are additional request rows on the same evaluation.

## FK Delete Policy

Default: `ON DELETE RESTRICT`.

Financial and audit history must not disappear because a parent row was deleted.

Exception:

- `stock_evaluation_details.evaluation_id` uses `ON DELETE CASCADE`

Details have no independent value without the parent evaluation.

## Index Policy

PK and UNIQUE already create indexes.

Additional indexes exist only for lookup patterns not covered by UNIQUE, for example latest evaluations by run/date and orders by run/time.

UNIQUE `(instrument_id, trade_date)` and UNIQUE `(strategy_run_id, snapshot_date)` are scanned in either direction, so extra DESC copies were not created.

## Schema Location

- `db/init/001_create_schema.sql` creates schema `bjstock` only
- `db/migrations/0001_initial_business_schema.sql` creates business tables
- `db/migrations/0002_phase1_schema_hardening.sql` closes `value_type` and makes AI request/result 1:1
- `bjstock.schema_migrations` records applied migration versions
- `scripts/db-migrate.ps1` applies pending files in order and will not re-run an applied version
