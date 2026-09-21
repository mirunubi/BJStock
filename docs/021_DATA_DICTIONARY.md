# BJStock Data Dictionary

Phase 1 laboratory schema in `bjstock`. All timestamps are UTC `TIMESTAMPTZ`. Trade and evaluation days are `DATE`.

`schema_migrations` is a laboratory tracking table, not a business domain table.

---

## instruments

**Purpose**

Listed instruments that later receive market bars, factors, evaluations, and paper trades.

**PK**

- `id BIGINT IDENTITY`

**FK**

- none

**Unique**

- `UNIQUE (market, symbol)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| market | TEXT | e.g. KRX |
| symbol | TEXT | e.g. 005930 |
| name | TEXT | display name |
| sector | TEXT | nullable |
| industry | TEXT | nullable |
| currency | TEXT | default KRW |
| is_active | BOOLEAN | default TRUE |
| listed_date | DATE | nullable |
| delisted_date | DATE | nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | application-maintained |

**Important Constraints**

- `market`, `symbol`, `name`, `currency` are non-empty
- `delisted_date >= listed_date` when both are present

**Lifecycle**

Insert when an instrument is first tracked. Deactivate with `is_active` rather than deleting if history exists.

---

## market_daily_bars

**Purpose**

Daily OHLCV used by factor calculation.

**PK**

- `id`

**FK**

- `instrument_id → instruments.id` RESTRICT

**Unique**

- `UNIQUE (instrument_id, trade_date)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| trade_date | DATE | session date |
| open_price / high_price / low_price / close_price | NUMERIC(18, 4) | never FLOAT |
| volume | BIGINT | shares |
| trading_value | NUMERIC(20, 4) | nullable |
| source | TEXT | data origin |
| collected_at | TIMESTAMPTZ | collection time UTC |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- prices `>= 0`
- `volume >= 0`
- `high_price >= low_price`
- `trading_value IS NULL OR trading_value >= 0`

**Lifecycle**

One row per instrument per trade date. Append-only in normal operation.

---

## factor_definitions

**Purpose**

Catalog of named factors.

**PK**

- `id`

**FK**

- none

**Unique**

- `UNIQUE (factor_code)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| factor_code | TEXT | stable code |
| factor_name | TEXT | display name |
| category | TEXT | FINANCIAL, VALUATION, MOMENTUM, VOLUME, FLOW, MARKET, TECHNICAL, OTHER |
| description | TEXT | nullable |
| value_type | TEXT | non-empty open vocabulary in Phase 1 |
| higher_is_better | BOOLEAN | scoring direction |
| is_active | BOOLEAN | default TRUE |
| created_at / updated_at | TIMESTAMPTZ | |

**Important Constraints**

- category CHECK list above
- codes/names/value_type non-empty

**Lifecycle**

Define before computing `factor_values` or attaching weights. Do not reuse a code for a different meaning.

---

## factor_values

**Purpose**

Computed factor output for an instrument and date.

**PK**

- `id`

**FK**

- `instrument_id → instruments.id` RESTRICT
- `factor_id → factor_definitions.id` RESTRICT

**Unique**

- `UNIQUE (instrument_id, factor_id, evaluation_date, calculation_version)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| evaluation_date | DATE | |
| raw_value | NUMERIC(20, 8) | nullable |
| normalized_score | NUMERIC(7, 4) | nullable, 0..100 |
| source | TEXT | |
| calculation_version | TEXT | formula/version tag |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- `normalized_score IS NULL OR (0 <= normalized_score <= 100)`

**Lifecycle**

Write when a calculation version produces a value. Keep raw and normalized together when both exist.

---

## strategies

**Purpose**

Logical strategy identity. Settings live on versions.

**PK**

- `id`

**FK**

- none

**Unique**

- `UNIQUE (strategy_code)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| strategy_code | TEXT | |
| strategy_name | TEXT | |
| description | TEXT | nullable |
| is_active | BOOLEAN | default TRUE |
| created_at / updated_at | TIMESTAMPTZ | |

**Important Constraints**

- code/name non-empty

**Lifecycle**

Create once. New behavior becomes a new `strategy_versions` row.

---

## strategy_versions

**Purpose**

Versioned thresholds and validity window. Treated as immutable after a forward test uses it.

**PK**

- `id`

**FK**

- `strategy_id → strategies.id` RESTRICT

**Unique**

- `UNIQUE (strategy_id, version_no)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| version_no | INTEGER | >= 1 |
| description | TEXT | nullable |
| buy_threshold | NUMERIC(7, 4) | 0..100 |
| sell_threshold | NUMERIC(7, 4) | 0..100 |
| valid_from / valid_to | DATE | nullable |
| status | TEXT | DRAFT, ACTIVE, RETIRED |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- `valid_to >= valid_from` when both present

**Lifecycle**

DRAFT while editing. ACTIVE when usable. RETIRED when no longer started for new runs. Do not overwrite a version that already has runs.

---

## strategy_factor_weights

**Purpose**

Per-version factor weights used to rebuild scores.

**PK**

- `id`

**FK**

- `strategy_version_id → strategy_versions.id` RESTRICT
- `factor_id → factor_definitions.id` RESTRICT

**Unique**

- `UNIQUE (strategy_version_id, factor_id)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| weight | NUMERIC(8, 6) | 0..1 |
| min_score / max_score | NUMERIC(7, 4) | nullable, 0..100 |
| enabled | BOOLEAN | default TRUE |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- `0 <= weight <= 1`
- `min_score <= max_score` when both present
- enabled weight sum = 1.0 is verified by SQL, not a trigger

**Lifecycle**

Insert with the version. Changing weights of a used version requires a new version.

---

## strategy_runs

**Purpose**

Independent forward-test / paper-trading book.

**PK**

- `id`

**FK**

- `strategy_version_id → strategy_versions.id` RESTRICT

**Unique**

- none beyond PK

A version may have many runs.

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| run_name | TEXT | |
| run_type | TEXT | Phase 1: PAPER only |
| start_date | DATE | |
| end_date | DATE | nullable |
| initial_cash | NUMERIC(20, 4) | > 0 |
| status | TEXT | DRAFT, READY, RUNNING, PAUSED, COMPLETED, CANCELLED |
| started_at / ended_at | TIMESTAMPTZ | nullable |
| created_at / updated_at | TIMESTAMPTZ | |

**Important Constraints**

- `initial_cash > 0`
- `end_date IS NULL OR end_date >= start_date`
- `run_type IN ('PAPER')`

**Lifecycle**

Create as DRAFT/READY, start RUNNING, then COMPLETED or CANCELLED. This row is the virtual-account unit.

---

## stock_evaluations

**Purpose**

Official daily decision record for one instrument in one run.

**PK**

- `id`

**FK**

- `strategy_run_id → strategy_runs.id` RESTRICT
- `instrument_id → instruments.id` RESTRICT

**Unique**

- `UNIQUE (strategy_run_id, instrument_id, evaluation_date)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| evaluation_date | DATE | |
| quant_score | NUMERIC(7, 4) | 0..100 |
| ai_score | NUMERIC(7, 4) | nullable, 0..100 |
| final_score | NUMERIC(7, 4) | 0..100 |
| quant_decision | TEXT | BUY, HOLD, SELL, NO_ACTION |
| final_decision | TEXT | BUY, HOLD, SELL, NO_ACTION |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- scores in 0..100
- decisions in the CHECK list

**Lifecycle**

One official row per run/instrument/day. Quant and final decisions stay separate so AI cannot silently replace the quant path.

---

## stock_evaluation_details

**Purpose**

Per-factor contribution that makes an evaluation replayable.

**PK**

- `id`

**FK**

- `evaluation_id → stock_evaluations.id` CASCADE
- `factor_id → factor_definitions.id` RESTRICT

**Unique**

- `UNIQUE (evaluation_id, factor_id)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| raw_value | NUMERIC(20, 8) | nullable |
| factor_score | NUMERIC(7, 4) | 0..100 |
| weight | NUMERIC(8, 6) | 0..1 |
| weighted_score | NUMERIC(12, 8) | |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- child-only; deleted with parent evaluation

**Lifecycle**

Insert with the evaluation. Not kept without the parent.

---

## positions

**Purpose**

Current holdings cache for a run.

**PK**

- `id`

**FK**

- `strategy_run_id → strategy_runs.id` RESTRICT
- `instrument_id → instruments.id` RESTRICT

**Unique**

- `UNIQUE (strategy_run_id, instrument_id)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| quantity | NUMERIC(20, 4) | >= 0 |
| average_price | NUMERIC(18, 4) | >= 0 |
| realized_profit | NUMERIC(20, 4) | default 0 |
| updated_at | TIMESTAMPTZ | |

**Important Constraints**

- quantity/average_price `>= 0`

**Lifecycle**

Upsert after fills. Reconstruct true P&L from `orders` / `executions`. A zero quantity may remain as cache or be removed later by application policy.

---

## orders

**Purpose**

Virtual paper orders.

**PK**

- `id`

**FK**

- `strategy_run_id → strategy_runs.id` RESTRICT
- `instrument_id → instruments.id` RESTRICT
- `evaluation_id → stock_evaluations.id` RESTRICT, nullable

**Unique**

- `UNIQUE (client_order_id)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| client_order_id | TEXT | portable UUID string |
| side | TEXT | BUY, SELL |
| order_type | TEXT | MARKET, LIMIT |
| requested_price | NUMERIC(18, 4) | nullable |
| quantity | NUMERIC(20, 4) | > 0 |
| status | TEXT | CREATED, VIRTUAL_FILLED, CANCELLED, REJECTED |
| created_at | TIMESTAMPTZ | |
| executed_at / cancelled_at | TIMESTAMPTZ | nullable |

**Important Constraints**

- `evaluation_id` nullable for future manual virtual orders
- side/type/status CHECK lists

**Lifecycle**

Insert CREATED, then VIRTUAL_FILLED, CANCELLED, or REJECTED. Do not delete after fills exist.

---

## executions

**Purpose**

Virtual fills. Ledger source of truth with orders.

**PK**

- `id`

**FK**

- `order_id → orders.id` RESTRICT

**Unique**

- none beyond PK

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| execution_price | NUMERIC(18, 4) | >= 0 |
| quantity | NUMERIC(20, 4) | > 0 |
| commission | NUMERIC(20, 4) | >= 0, default 0 |
| tax | NUMERIC(20, 4) | >= 0, default 0 |
| slippage | NUMERIC(18, 4) | default 0, signed allowed |
| executed_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- `quantity > 0`

**Lifecycle**

Append-only fill history. Never CASCADE-deleted from orders.

---

## portfolio_daily_snapshots

**Purpose**

Daily book snapshot for performance analytics.

**PK**

- `id`

**FK**

- `strategy_run_id → strategy_runs.id` RESTRICT

**Unique**

- `UNIQUE (strategy_run_id, snapshot_date)`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| snapshot_date | DATE | |
| cash | NUMERIC(20, 4) | |
| market_value | NUMERIC(20, 4) | |
| total_asset | NUMERIC(20, 4) | |
| daily_profit | NUMERIC(20, 4) | |
| daily_return | NUMERIC(12, 8) | |
| cumulative_profit | NUMERIC(20, 4) | |
| cumulative_return | NUMERIC(12, 8) | |
| drawdown | NUMERIC(12, 8) | |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- one snapshot per run per date

**Lifecycle**

Write once per session date after valuations. Later used for MDD, volatility, monthly return, and benchmark comparison.

---

## ai_advice_requests

**Purpose**

Optional AI request log attached to an evaluation.

**PK**

- `id`

**FK**

- `evaluation_id → stock_evaluations.id` RESTRICT

**Unique**

- none beyond PK

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| provider | TEXT | replaceable vendor |
| model | TEXT | |
| prompt_version | TEXT | |
| request_payload | TEXT | serialized JSON-as-text allowed |
| requested_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- never store API keys or secrets
- provider/model/prompt_version non-empty

**Lifecycle**

Insert only when AI is enabled. Trading continues if this table is empty.

---

## ai_advice_results

**Purpose**

Optional AI output. Advisory only.

**PK**

- `id`

**FK**

- `request_id → ai_advice_requests.id` RESTRICT

**Unique**

- none beyond PK. Multiple results per request are allowed.

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| recommendation | TEXT | BUY, HOLD, SELL, NO_OPINION |
| confidence | NUMERIC(6, 4) | nullable, 0..1 |
| summary | TEXT | |
| reasoning_summary | TEXT | user-facing summary only |
| risk_notes | TEXT | |
| raw_response | TEXT | |
| used_in_decision | BOOLEAN | default FALSE |
| created_at | TIMESTAMPTZ | |

**Important Constraints**

- AI is not an execution actor
- chain-of-thought is not a required stored field

**Lifecycle**

Store after a request returns. `used_in_decision` records whether advice was attached to the decision record. It does not authorize an order.

---

## schema_migrations

**Purpose**

Laboratory migration history for `scripts/db-migrate.ps1`.

**PK**

- `version TEXT`

**Unique**

- `filename`

**Main Columns**

| Column | Type | Notes |
| --- | --- | --- |
| version | TEXT | numeric prefix, e.g. 0001 |
| filename | TEXT | |
| applied_at | TIMESTAMPTZ | |

**Lifecycle**

Inserted by the runner after a successful file apply. Never re-run an applied version.
