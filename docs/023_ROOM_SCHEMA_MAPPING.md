# BJStock Room Schema Mapping

Phase 2 maps PostgreSQL laboratory tables to Room / SQLite.

Storage rules:

- KRW money/price: `Long` integer won. No `Double`.
- Scores 0..100: `Long` scaled by 10_000.
- Weights 0..1: `Long` scaled by 1_000_000.
- Returns/drawdown: `Long` scaled by 100_000_000.
- Unbounded factor `raw_value`: decimal `String`.
- `DATE`: `LocalDate` stored as epoch-day `Long`.
- `TIMESTAMPTZ`: `Instant` stored as epoch-millis `Long` (UTC).
- Closed vocabularies: Kotlin enum stored as String code, never ordinal.
- PostgreSQL CHECK constraints are not reproduced as SQLite CHECK. They will be re-validated in application code in later phases.

`schema_migrations` is laboratory-only and is not a Room entity.

---

## instruments

**PostgreSQL Table:** `bjstock.instruments`

**Room Entity:** `InstrumentEntity` / `instruments`

**PK:** `id BIGINT identity` → `@PrimaryKey(autoGenerate = true) Long`

**FK:** none

**Unique:** `(market, symbol)`

**Numeric Mapping:** none

**Date/Time Mapping:** `listed_date`/`delisted_date` LocalDate; `created_at`/`updated_at` Instant UTC

**Constraint Difference:** CHECK non-empty and delisted>=listed are INTENTIONAL omissions. Application validation later.

---

## market_daily_bars

**PostgreSQL Table:** `bjstock.market_daily_bars`

**Room Entity:** `MarketDailyBarEntity` / `market_daily_bars`

**PK:** `id` autoGenerate Long

**FK:** `instrument_id → instruments.id` RESTRICT

**Unique:** `(instrument_id, trade_date)`

**Numeric Mapping:** prices/trading_value `NUMERIC` → `Long` won. `volume` BIGINT → Long. INTENTIONAL scale 0 vs PG scale 4.

**Date/Time Mapping:** `trade_date` LocalDate; `collected_at`/`created_at` Instant UTC

**Constraint Difference:** price/volume CHECKs INTENTIONAL omission. Phase 3-C validates OHLC in application code before UPSERT. Schema version remains 1; DAO/repository were added without changing columns.

Phase 3-C persistence rules:

- Existing `instruments` row is required. No auto-create.
- UPSERT by `(instrument_id, trade_date)` using INSERT or UPDATE, not `INSERT OR REPLACE`.
- `source` = `KIS` (`MarketDataSource.KIS`).
- `created_at` is kept on update; `collected_at` is refreshed.
- Daily bars only. Current quotes are not stored.
- Room schema version stays 1.

---

## factor_definitions

**PostgreSQL Table:** `bjstock.factor_definitions`

**Room Entity:** `FactorDefinitionEntity` / `factor_definitions`

**PK:** `id`

**FK:** none

**Unique:** `factor_code`

**Numeric Mapping:** none

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** category/value_type CHECK become Kotlin enums stored as String. Closed lists remain the same codes.

---

## factor_values

**PostgreSQL Table:** `bjstock.factor_values`

**Room Entity:** `FactorValueEntity` / `factor_values`

**PK:** `id`

**FK:** `instrument_id → instruments.id` RESTRICT; `factor_id → factor_definitions.id` RESTRICT

**Unique:** `(instrument_id, factor_id, evaluation_date, calculation_version)`

**Numeric Mapping:** `raw_value` NUMERIC(20,8) → String decimal. `normalized_score` NUMERIC(7,4) → Long * 10_000.

**Date/Time Mapping:** `evaluation_date` LocalDate; `created_at` Instant UTC

**Constraint Difference:** 0..100 score CHECK INTENTIONAL omission.

---

## strategies

**PostgreSQL Table:** `bjstock.strategies`

**Room Entity:** `StrategyEntity` / `strategies`

**PK:** `id`

**FK:** none

**Unique:** `strategy_code`

**Numeric Mapping:** none

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** non-empty CHECKs INTENTIONAL omission.

---

## strategy_versions

**PostgreSQL Table:** `bjstock.strategy_versions`

**Room Entity:** `StrategyVersionEntity` / `strategy_versions`

**PK:** `id`

**FK:** `strategy_id → strategies.id` RESTRICT

**Unique:** `(strategy_id, version_no)`

**Numeric Mapping:** `buy_threshold`/`sell_threshold` NUMERIC(7,4) → Long * 10_000

**Date/Time Mapping:** `valid_from`/`valid_to` LocalDate; `created_at` Instant UTC

**Constraint Difference:** status TEXT CHECK → enum String. version/threshold CHECKs INTENTIONAL omission.

---

## strategy_factor_weights

**PostgreSQL Table:** `bjstock.strategy_factor_weights`

**Room Entity:** `StrategyFactorWeightEntity` / `strategy_factor_weights`

**PK:** `id`

**FK:** `strategy_version_id → strategy_versions.id` RESTRICT; `factor_id → factor_definitions.id` RESTRICT

**Unique:** `(strategy_version_id, factor_id)`

**Numeric Mapping:** `weight` NUMERIC(8,6) → Long * 1_000_000. min/max score Long * 10_000.

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** weight 0..1 CHECK INTENTIONAL omission. Sum=1.0 remains SQL/app verification, not a trigger.

---

## strategy_runs

**PostgreSQL Table:** `bjstock.strategy_runs`

**Room Entity:** `StrategyRunEntity` / `strategy_runs`

**PK:** `id`

**FK:** `strategy_version_id → strategy_versions.id` RESTRICT

**Unique:** none beyond PK

**Numeric Mapping:** `initial_cash` NUMERIC(20,4) → Long won

**Date/Time Mapping:** start/end LocalDate; started/ended/created/updated Instant UTC

**Constraint Difference:** `run_type` PAPER-only and status CHECK → enums stored as String. cash/date CHECKs INTENTIONAL omission.

---

## stock_evaluations

**PostgreSQL Table:** `bjstock.stock_evaluations`

**Room Entity:** `StockEvaluationEntity` / `stock_evaluations`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT; `instrument_id → instruments.id` RESTRICT

**Unique:** `(strategy_run_id, instrument_id, evaluation_date)`

**Numeric Mapping:** scores NUMERIC(7,4) → Long * 10_000

**Date/Time Mapping:** `evaluation_date` LocalDate; `created_at` Instant UTC

**Constraint Difference:** decision CHECK → enum String. score range CHECK INTENTIONAL omission.

---

## stock_evaluation_details

**PostgreSQL Table:** `bjstock.stock_evaluation_details`

**Room Entity:** `StockEvaluationDetailEntity` / `stock_evaluation_details`

**PK:** `id`

**FK:** `evaluation_id → stock_evaluations.id` CASCADE; `factor_id → factor_definitions.id` RESTRICT

**Unique:** `(evaluation_id, factor_id)`

**Numeric Mapping:** `raw_value` String decimal; `factor_score` Long * 10_000; `weight` Long * 1_000_000; `weighted_score` Long * 100_000_000

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** score/weight CHECKs INTENTIONAL omission.

---

## positions

**PostgreSQL Table:** `bjstock.positions`

**Room Entity:** `PositionEntity` / `positions`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT; `instrument_id → instruments.id` RESTRICT

**Unique:** `(strategy_run_id, instrument_id)`

**Numeric Mapping:** quantity/average_price/realized_profit → Long (shares and won)

**Date/Time Mapping:** `updated_at` Instant UTC

**Constraint Difference:** non-negative CHECKs INTENTIONAL omission.

---

## orders

**PostgreSQL Table:** `bjstock.orders`

**Room Entity:** `OrderEntity` / `orders`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT; `instrument_id → instruments.id` RESTRICT; `evaluation_id → stock_evaluations.id` RESTRICT nullable

**Unique:** `client_order_id`

**Numeric Mapping:** `requested_price` Long won nullable; `quantity` Long shares

**Date/Time Mapping:** created/executed/cancelled Instant UTC

**Constraint Difference:** side/type/status CHECK → enum String. quantity CHECK INTENTIONAL omission.

---

## executions

**PostgreSQL Table:** `bjstock.executions`

**Room Entity:** `ExecutionEntity` / `executions`

**PK:** `id`

**FK:** `order_id → orders.id` RESTRICT

**Unique:** none beyond PK

**Numeric Mapping:** price/quantity/commission/tax/slippage → Long won or shares

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** non-negative/positive CHECKs INTENTIONAL omission.

---

## portfolio_daily_snapshots

**PostgreSQL Table:** `bjstock.portfolio_daily_snapshots`

**Room Entity:** `PortfolioDailySnapshotEntity` / `portfolio_daily_snapshots`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT

**Unique:** `(strategy_run_id, snapshot_date)`

**Numeric Mapping:** cash/market_value/total_asset/profits → Long won. daily_return/cumulative_return/drawdown → Long * 100_000_000

**Date/Time Mapping:** `snapshot_date` LocalDate; `created_at` Instant UTC

**Constraint Difference:** none beyond unique.

---

## ai_advice_requests

**PostgreSQL Table:** `bjstock.ai_advice_requests`

**Room Entity:** `AiAdviceRequestEntity` / `ai_advice_requests`

**PK:** `id`

**FK:** `evaluation_id → stock_evaluations.id` RESTRICT

**Unique:** none beyond PK

**Numeric Mapping:** none

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** non-empty CHECKs INTENTIONAL omission. Secrets must never be stored.

---

## ai_advice_results

**PostgreSQL Table:** `bjstock.ai_advice_results`

**Room Entity:** `AiAdviceResultEntity` / `ai_advice_results`

**PK:** `id`

**FK:** `request_id → ai_advice_requests.id` RESTRICT

**Unique:** `request_id` (1:1)

**Numeric Mapping:** `confidence` NUMERIC(6,4) → Long * 10_000 nullable

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** recommendation CHECK → enum String. 0..1 confidence CHECK INTENTIONAL omission.
