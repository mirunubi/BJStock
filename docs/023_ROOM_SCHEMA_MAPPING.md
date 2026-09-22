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

**Constraint Difference:** CHECK non-empty, board, instrument_type, and delisted>=listed are INTENTIONAL omissions in SQLite. Application validation and PostgreSQL CHECKs remain the source of closed lists.

Phase 3-D added `standard_code`, `board`, and `instrument_type` in Room version 2. Phase 5 adds pinned `factor_calculation_version` in Room version 3. Phase 6 adds `cash_ledger` in Room version 4 and extends order status vocabulary with `PENDING_EXECUTION`. Phase 6.1 adds `paper_trading_policies` in Room version 5. Phase 9 adds `strategy_run_instruments` and `forward_test_cycles` in Room version 6. Phase 9.1 adds `themes`, `theme_instruments`, `strategy_signal_rules`, `trade_audit_logs`, and `api_error_logs` in Room version 7. Explicit Migration(1, 2) through Migration(6, 7) are registered; `fallbackToDestructiveMigration` is not used.

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
- Room schema version was 1 in Phase 3-C and became 2 in Phase 3-D for instrument master columns.

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

**Constraint Difference:** 0..100 score CHECK INTENTIONAL omission. Phase 4 writes SUCCESS scores already clamped to 0..100, stored as Long × 10_000.

Phase 4 persistence:

- System factor source is `BJSTOCK_MARKET_ENGINE`, not `KIS`.
- UPSERT by `(instrument_id, factor_id, evaluation_date, calculation_version)`.
- `NO_DATA` / `INSUFFICIENT_HISTORY` / `INVALID_DATA` are not inserted.
- Room schema version stayed 2 for Phase 4. Phase 5 changes it to 3 for strategy factor-version pinning.

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

**Columns:** `factor_calculation_version` TEXT is a nonblank pinned registry version. Migration 2→3 gives existing rows `v1`.

**Numeric Mapping:** `weight` NUMERIC(8,6) → Long * 1_000_000. min/max score Long * 10_000.

**Date/Time Mapping:** Instant UTC

**Constraint Difference:** weight 0..1 and nonblank-version CHECKs are application validation in Room. Enabled sum=1.0 is exact scaled-integer activation validation, not a trigger.

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

## strategy_run_instruments

**PostgreSQL Table:** `bjstock.strategy_run_instruments`

**Room Entity:** `StrategyRunInstrumentEntity` / `strategy_run_instruments`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT; `instrument_id → instruments.id` RESTRICT

**Unique:** `(strategy_run_id, instrument_id)`

**Numeric Mapping:** none

**Date/Time Mapping:** `created_at` Instant UTC

**Constraint Difference:** none beyond unique. Application enforces DRAFT-only edits. Added in Room version 6 via Migration(5, 6).

---

## forward_test_cycles

**PostgreSQL Table:** `bjstock.forward_test_cycles`

**Room Entity:** `ForwardTestCycleEntity` / `forward_test_cycles`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT

**Unique:** `(strategy_run_id, market_date)`

**Numeric Mapping:** none (`attempt_count` Int)

**Date/Time Mapping:** `market_date` LocalDate; started/completed/created/updated Instant UTC

**Constraint Difference:** status/stage CHECKs INTENTIONAL omission — enums stored as String. `error_message` must never contain secrets. Added in Room version 6 via Migration(5, 6).

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

## cash_ledger

**PostgreSQL Table:** `bjstock.cash_ledger`

**Room Entity:** `CashLedgerEntity` / `cash_ledger`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT

**Numeric Mapping:** `amount` / `balance_after` → Long won (signed amount)

**Date/Time Mapping:** `event_date` LocalDate; `created_at` Instant UTC

**Constraint Difference:** PostgreSQL CHECK event types / balance_after >= 0 are application-validated in Room.

---

## paper_trading_policies

**PostgreSQL Table:** `bjstock.paper_trading_policies`

**Room Entity:** `PaperTradingPolicyEntity` / `paper_trading_policies`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT

**Unique:** `UNIQUE (strategy_run_id)` — exactly one snapshot per strategy run

**Numeric Mapping:** `buy_allocation_rate` / `commission_rate` / `sell_tax_rate` → Long * `WEIGHT_FACTOR` (1_000_000). PostgreSQL stores the same rates as `NUMERIC(8,6)`. `slippage_bps` → Long/Int.

**Date/Time Mapping:** `created_at` Instant UTC (snapshot creation wall clock)

**Constraint Difference:** PostgreSQL CHECKs enforce policy vocabularies, non-empty `policy_version`, and `short_selling_allowed = FALSE`. Room validates via application enums / service guards.

---

## orders

**PostgreSQL Table:** `bjstock.orders`

**Room Entity:** `OrderEntity` / `orders`

**PK:** `id`

**FK:** `strategy_run_id → strategy_runs.id` RESTRICT; `instrument_id → instruments.id` RESTRICT; `evaluation_id → stock_evaluations.id` RESTRICT nullable

**Unique:** `client_order_id`

**Numeric Mapping:** `requested_price` Long won nullable; `quantity` Long shares

**Date/Time Mapping:** created/cancelled Instant wall-clock UTC. `executed_at` stores market execution date as UTC midnight of that trading day.

**Constraint Difference:** side/type/status CHECK → enum String including `PENDING_EXECUTION`. quantity may be 0 for pending BUY / REJECTED.

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

---

## themes / theme_instruments / strategy_signal_rules / trade_audit_logs / api_error_logs

**PostgreSQL:** `0008_theme_rule_audit_logging.sql`

**Room:** version 7 entities + `MIGRATION_6_7`

CHECK vocabularies enforced in Kotlin enums / application services. Trade audit is append-only; API errors use rolling 7-day cleanup.
