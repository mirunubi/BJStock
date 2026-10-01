# Intraday 15-Minute Multi-Factor Model Architecture

| Item | Value |
| --- | --- |
| Document Status | **APPROVED — Human Architecture Approved** |
| Human Approval Date | 2026-10-02 |
| ImplementationAuthority | **NONE** |
| Baseline | `f66c85d` |
| Scope | Architecture only |
| Phase / Gate | Phase 12 — Intraday Multi-Factor Strategy Architecture / Phase 12-A |

This approval authorizes the Phase 12 architecture and the Human product decisions recorded in this document (§36).

It does **NOT** authorize:

- Kotlin implementation
- Room / schema changes
- PostgreSQL migration
- provider integration
- foreground service
- runtime probe
- scheduler changes
- trading-engine changes
- UI changes
- 12-B1
- 12-B2
- 12-C or later gates

Each subsequent gate requires separate authority.

Nothing in this document is implemented. No Kotlin, Room, PostgreSQL, migration, scheduler, Worker, KIS, factor, strategy, trading, UI, or test change is authorized by it. Field and entity names below are conceptual, not schema.

## 0. Reading Guide, Approved Principles, and Fail-Closed Requirement

Every statement carries one of these labels:

| Label | Meaning |
| --- | --- |
| **CURRENT VERIFIED** | Current BJStock behavior, checked against code at `f66c85d` (file named) and/or a canonical doc |
| **HUMAN APPROVED** | Human architecture / product decision approved on 2026-10-02 (§0.1, §0.2, §36). Architecture only; not implementation authority |
| **PROPOSED** | Architecture direction written during review. With the 2026-10-02 approval it is the approved architecture direction where consistent with §36; concrete design, names, keys, and schema are decided in the named later gate. Where a PROPOSED statement and a §36 decision differ, §36 governs. Not implemented |
| **OPEN** | Needs a later gate's evidence or a later Human decision (e.g., numeric values after 12-B1 / 12-B2) |
| **DEFERRED** | Deliberately out of the first Intraday MVP |

### 0.1 Approved Architecture Principles (HUMAN APPROVED — 2026-10-02)

1. DAILY remains unchanged (§3).
2. INTRADAY_15M is additive (§3).
3. Paper trading only (§4).
4. No real brokerage order endpoint; KIS `/trading/` stays forbidden (§1, §4).
5. No look-ahead (§11, §12).
6. No retroactive live paper trade (§21, §29, §30).
7. No decision without trustworthy required data (§12, §13, §0.2).
8. Fail-Closed on missing / stale / late / unverifiable inputs (§0.2).
9. Every expected slot produces auditable evidence (§0.2, §32).
10. Exact observation and policy provenance support replay (§12, §15, §32).
11. Risk is separate from the directional Model decision (§8, §9, §20).
12. Each Run remains independently reproducible (§6, §26).

### 0.2 Fail-Closed + Intraday Audit Requirement (HUMAN APPROVED — PRODUCT REQUIREMENT)

When required information is missing, stale, late, unverifiable, or the runtime cannot complete the slot safely:

```text
NO AUTOMATIC PAPER BUY / SELL ACTION.
Record sufficient audit / operational evidence to explain why no trade occurred.
```

Every expected Intraday slot must leave an auditable outcome. Future architecture must at minimum distinguish evidence for:

| Outcome | Minimum evidence |
| --- | --- |
| NORMAL EVALUATION | input observations used (exact versions); factor values / scores; StrategyVersion; decision; risk result; sizing; order / fill if created |
| HOLD / NO_ACTION | no order; reason preserved |
| RISK BLOCK | original model decision preserved; risk reason preserved; no order |
| MISSING / STALE DATA | affected source / factor; required vs available state; no fabricated zero score; no weight renormalization; no trade |
| NETWORK / PROVIDER FAILURE | safe provider / error classification; slot outcome; no trade if a trustworthy input set was unavailable |
| LATE / MISSED / FAILED | slot identity; `decision_cutoff_at` / `decision_deadline_at`; failure reason; no retroactive trade |
| ORDER / EXECUTION | `decision_at`; `order_created_at`; execution price time; price source / provenance; policy snapshot; proof that execution occurred strictly after the order existed |

This is a product requirement, not an implementation. No new audit system is built in this gate. Future design should preferentially extend the existing `trade_audit_logs` and `operational_events` where semantically appropriate rather than inventing a parallel audit subsystem. Exact schema ownership is decided in 12-C / 12-K.

Canonical references: [000](000_PROJECT_OVERVIEW.md), [010](010_ARCHITECTURE.md), [020](020_DATA_ARCHITECTURE.md), [021](021_DATA_DICTIONARY.md), [022](022_ERD.md), [023](023_ROOM_SCHEMA_MAPPING.md), [030](030_DEVELOPMENT_PLAN.md), [060](060_DECISION_LOG.md), [081](081_KIS_MARKET_DATA.md), [082](082_MARKET_DATA_PERSISTENCE.md), [084](084_HISTORICAL_SYNC.md), [090](090_FACTOR_ENGINE.md), [091](091_FACTOR_CATALOG.md), [100](100_STRATEGY_ENGINE.md), [101](101_STRATEGY_EVALUATION.md), [110](110_PAPER_TRADING_ENGINE.md), [111](111_VIRTUAL_ACCOUNT.md), [112](112_PORTFOLIO_SNAPSHOT.md), [113](113_PAPER_TRADING_POLICY.md), [120](120_PERFORMANCE_ANALYTICS.md), [140](140_FORWARD_TEST_ORCHESTRATION.md), [141](141_FORWARD_TEST_OPERATIONS.md), [145](145_THEMES_AND_WATCHLISTS.md), [146](146_SIGNAL_RULES.md), [147](147_TRADE_AUDIT_LOG.md), [150](150_OPERATIONAL_RELIABILITY_STANDARD.md), [151](151_BJSTOCK_UI_UX_DESIGN.md).

No canonical document is changed by this document.

---

## 1. Current Architecture Baseline (CURRENT VERIFIED)

Room database version 12 (`core/database/BJStockDatabase.kt`). Runtime is a local-only Android app; Docker PostgreSQL is a schema laboratory only (D-007, D-022). The APK requests only `android.permission.INTERNET` (`AndroidManifest.xml`); `targetSdk = 36`, `minSdk = 28` (`app/build.gradle.kts`).

Daily pipeline ([140](140_FORWARD_TEST_ORCHESTRATION.md), `core/forward/ForwardTestOrchestrator.kt`):

```text
universe daily-bar sync (KIS, read-only)
  → per run, per market date D (catch-up ASC):
      PENDING_FILLS → FACTORS → EVALUATIONS → ORDER_CREATION → SNAPSHOT → COMPLETE
```

Market data provider surface (`core/kis/market/KisMarketApiConfig.kt`, `core/network/kis/KisMarketApi.kt`):

| Endpoint | Use | Persisted |
| --- | --- | --- |
| `/uapi/domestic-stock/v1/quotations/inquire-price` | domestic stock current quote, KRX `J` only | no (UI only, D-051) |
| `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | domestic stock daily bars, period `D` only, ADJUSTED | `market_daily_bars` |
| `/oauth2/tokenP` | OAuth token | encrypted store, not Room |
| Official MST zip files | KOSPI / KOSDAQ instrument master | `instruments` |

`/trading/` is blocked by `KisReadOnlyGuard` / `KisReadOnlyInterceptor` (D-038). A repository search for intraday / minute bars / WebSocket / futures / Treasury / FX / overseas finds **no** provider code and no doc beyond [082](082_MARKET_DATA_PERSISTENCE.md) stating that WebSocket ticks are not stored.

Scale facts (read-only copy of the device database, Phase 11 B10 evidence snapshot, 2026-10-01): 3,545 active instruments (KOSPI 1,778 / KOSDAQ 1,767); current Runs each have a 1-instrument universe.

## 2. DAILY-Specific Constraints (CURRENT VERIFIED)

| # | Assumption | Evidence | Blocks multiple evaluations per trading day? |
| --- | --- | --- | --- |
| 1 | `market_daily_bars` is DATE based, UNIQUE `(instrument_id, trade_date)` | `MarketDailyBarEntity` | Yes — no place for intraday bars |
| 2 | Factor calculation takes `asOfDate: LocalDate` | `FactorCalculator.calculate(instrumentId, asOfDate, bars)`, [090](090_FACTOR_ENGINE.md) | Yes — no time-of-day input |
| 3 | `factor_values` UNIQUE `(instrument_id, factor_id, evaluation_date, calculation_version)`; `instrument_id` NOT NULL | `FactorValueEntity` | Yes — one value per day; also **no non-instrument scope** (macro / FX / theme cannot be stored without a fake instrument) |
| 4 | Strategy version pins each factor's calculation version | `StrategyFactorWeightEntity.factor_calculation_version`, D-072 | No — reusable |
| 5 | A Run owns exactly one strategy version | `StrategyRunEntity.strategy_version_id`, D-012 | No — reusable |
| 6 | Run universe immutable after READY | `StrategyRunService.requireDraft`, D-119 | No — reusable |
| 7 | `stock_evaluations` UNIQUE `(strategy_run_id, instrument_id, evaluation_date)`; duplicate → `ALREADY_EVALUATED` | `StockEvaluationEntity`, D-078 | **Yes** |
| 8 | `forward_test_cycles` UNIQUE `(strategy_run_id, market_date)` | `ForwardTestCycleEntity` | **Yes** |
| 9 | Execution is `NEXT_TRADING_DAY_OPEN` (only enum value); fill looks up the next **daily** bar after the signal date | `ExecutionPricePolicy`, `ProcessPendingOrdersUseCase` (`require(... NEXT_TRADING_DAY_OPEN)`, `findNextTradingBar[AsOf]`) | **Yes** |
| 10 | `executed_at` = simulated market date as **UTC midnight** | `MarketExecutionTime.of`, D-096 | **Yes** — no time-of-day |
| 11 | Portfolio snapshots daily, UNIQUE `(strategy_run_id, snapshot_date)`, valued at exact daily close | `PortfolioDailySnapshotEntity`, D-089 | Yes for intraday valuation |
| 12 | Daily Auto target 07:00 KST, one-time work per calendar day | `ForwardTestConfig.AUTO_TARGET_TIME`, D-168 | Yes — one wake-up per day |
| 13 | Operational cutoff 18:00 KST decides `through_date` | `ForwardTestConfig.OPERATIONAL_CUTOFF`, D-121 | Yes — day granularity |
| 14 | Each Run is an independent virtual account | D-017, D-082 | No — reusable |
| 15 | KIS `/trading/` forbidden | D-038 | No — preserved |
| 16 | Signal metric is `DAILY_CHANGE_PCT` only (close vs previous trading-day close) | `SignalMetricCode`, `SignalRuleEngine.evaluateDailyChangePct` | Yes |
| 17 | Holding period = calendar days between UTC-midnight execution dates | `PerformanceAnalyticsService` (`ChronoUnit.DAYS.between`), D-103 | Yes for minute holding |
| 18 | Catch-up processes every missed market date ASC | D-122, [140](140_FORWARD_TEST_ORCHESTRATION.md) | Correct for DAILY; **wrong for live intraday** (see §29–30) |
| 19 | `schedule_instance_id` format is canonical daily `auto:<date>:0700:KST`, validated | `SafeLogText.scheduleInstanceId`, [150](150_OPERATIONAL_RELIABILITY_STANDARD.md) 20.9.4 | Yes for intraday slots |
| 20 | One process-wide non-waiting single-flight guard for every Forward Test entry point | `ForwardTestExecutionCoordinator`, D-152 | Would make DAILY and INTRADAY block each other |
| 21 | Trading days are inferred from stored bars (no exchange calendar) | [140](140_FORWARD_TEST_ORCHESTRATION.md) catch-up | Insufficient: intraday must know a session exists **before** bars arrive |
| 22 | `trade_audit_logs.market_date` is a `LocalDate` | `TradeAuditLogEntity` | Partial — needs slot reference |
| 23 | Theme membership removal deletes the row; no membership history | `ThemeService.removeInstrument` → `deleteMembership` | Yes for point-in-time theme features |
| 24 | Run universe rows carry no theme provenance | `StrategyRunInstrumentEntity` (no theme column), `addThemeToUniverse` copies ids only | Yes for theme-scope features |

## 3. Preserve DAILY (HUMAN APPROVED principle — §0.1)

```text
BJStock
  ├─ DAILY                      (unchanged)
  │    ├─ market_daily_bars
  │    ├─ daily factors (asOfDate)
  │    ├─ one cycle per run / market date
  │    └─ NEXT_TRADING_DAY_OPEN simulation
  │
  └─ INTRADAY_15M               (additive)
       ├─ confirmed 15-minute bars
       ├─ multi-source point-in-time observations
       ├─ repeated slot evaluation
       └─ post-decision paper execution (price strictly after the order exists, §21)
```

Rules:

- INTRADAY is additive. DAILY code paths, keys, cutoff, 07:00 slot, and catch-up semantics stay as they are.
- Existing DAILY rows are never rewritten or migrated to look uniform. How a generalized table distinguishes DAILY from INTRADAY is decided in 12-C, subject to the next rule.
- **DAILY duplicate protection must not be weakened.** A design of the form "existing daily UNIQUE key + nullable intraday slot column, where NULL means DAILY" is **forbidden**: SQLite UNIQUE indexes treat NULLs as distinct, so multiple DAILY rows with the same `(run, instrument, evaluation_date)` and NULL slot would no longer conflict, removing the backstop behind `StockEvaluationDao` inserts (`OnConflictStrategy.ABORT` on `uq_stock_evaluations_run_instrument_date`) and the same pattern elsewhere. 12-C must choose a safe model, e.g. a non-null timeframe discriminator with a deterministic non-null key for every row, or a separate intraday entity / table. No choice is made here.
- No silent reinterpretation of DAILY semantics: a daily factor value, a `stock_evaluations` row keyed by `evaluation_date`, a `forward_test_cycles` row keyed by `market_date`, `DAILY_CHANGE_PCT`, `NEXT_TRADING_DAY_OPEN`, UTC-midnight `executed_at`, and calendar-day `holdingDays` keep their current meaning. Intraday concepts get new, explicit identities instead of reusing these.
- A Run is either DAILY or INTRADAY_15M for its whole life (§16, §23).

## 4. Canonical User Requirement

A user defines several **deterministic weighted models** and operates each on a chosen set of instruments or a theme, evaluated every 15 minutes during the trading session using only information known at that time, producing BUY / HOLD / SELL, simulated paper orders and fills, over a long forward test.

Example:

| Model | Inputs (illustrative) | Output |
| --- | --- | --- |
| A | US Treasury yield change, USD/KRW change, index-futures direction, futures volume ratio, stock 15M momentum, stock volume ratio | weighted score + BUY/SELL thresholds |
| B | Different feature set and/or weights, different rules | same contract |
| C | Another combination | same contract |

Operating examples: selected stocks with A; an HBM theme with B; another Run with C.

Input families named by the requesting user: US Treasury yields, USD/KRW, domestic / overseas futures price and volume, stock 15M price / volume factors, theme factors, and — if needed — account / invested-amount / risk state. The last family is treated as risk-gate and sizing input by default, not as a directional factor (§9, §10).

Fixed boundaries of the requirement:

- Evaluation repeats every 15 minutes during the trading session (§11, §31), not once per day.
- Output is BUY / HOLD / SELL (plus the existing `NO_ACTION` for failed gates, D-077).
- **Paper trading only.** Orders and fills are BJStock virtual records; KIS `/trading/` stays forbidden (D-002, D-036, D-038).

"Model" here is **not** machine learning:

```text
Feature / Observation → Factor (deterministic transform) → normalized score
  → weight → gates → weighted score → rule / threshold → decision
```

AI remains advisory only (D-006, D-108 – D-110). No AI trading path is proposed.

## 5. Is "Model" Already Strategy Version?

### 5.1 Options

| | A) Model = Strategy / Strategy Version | B) Separate Model / Model Version domain |
| --- | --- | --- |
| Mapping | Strategy `A` = model family; `strategy_versions` V1..Vn = Model A V1..Vn | New `models` / `model_versions`; strategy would reference a model |
| Weights, gates, thresholds, pinned calc versions | Already present (`strategy_factor_weights`, `buy_threshold` / `sell_threshold`, `min_score` / `max_score`, `factor_calculation_version`) | Duplicated or moved |
| Hard rules | Already present (`strategy_signal_rules`) | Duplicated or moved |
| Immutability | ACTIVE / RETIRED immutable, copy-to-new-DRAFT (D-073, D-137) | Must be re-implemented |
| Run binding | `strategy_runs.strategy_version_id` | New FK or indirection |
| Audit / replay | Evaluation snapshots already explain a decision from version + details (D-078) | Two version chains to reconcile |
| Migration impact | Additive columns only (timeframe, observation policy) | New tables plus remapping or a parallel concept |
| UI impact | UI may say "모델" for a strategy without renaming persisted concepts | New screens and concept users must learn |

Advantages / disadvantages:

| | Advantages | Disadvantages |
| --- | --- | --- |
| A) Model = Strategy Version | Reuses proven versioning, immutability, pinning, rules, evaluation snapshots, Run binding, A/B comparison; additive schema only | "Strategy" and "Model" wording differ between persistence and UI; a version must now also carry timeframe and observation policy |
| B) Separate Model domain | A model could in principle be reused by several strategies with different surrounding policy | Duplicates versioning and immutability; two version chains in audit; new migration and UI concept; no current requirement needs model reuse across strategies (risk / sizing already live in the Run's policy) |

### 5.2 Gaps a Strategy Version does not cover today (CURRENT VERIFIED)

1. No declared evaluation timeframe (DAILY vs INTRADAY_15M).
2. Factors are instrument-scoped only (`factor_values.instrument_id` NOT NULL); macro / FX / futures / theme inputs have no home.
3. No per-input staleness policy (§13).
4. Signal rule metrics are daily only.

All four are **extensions** of a strategy version, not evidence that a separate Model domain is required.

### 5.3 Decision (HUMAN APPROVED — §36 #1)

The initial Intraday MVP reuses the existing StrategyVersion. A Model / B Model / C Model are product-facing conceptual names for independently versioned deterministic StrategyVersions (Strategy = model family, Strategy Version = exact model version). No separate Model domain (`models` table) is introduced for the first MVP. Existing ACTIVE immutability and version pinning remain canonical. The version is extended with an immutable evaluation timeframe and the inputs it needs. Persisted concepts are not renamed in this Gate.

## 6. Run-per-Model vs Multi-Model Portfolio

### 6.1 Option A — Run per Model

```text
Run A  Strategy A V3   universe: 005930, 000660        100M virtual cash
Run B  Strategy B V2   universe: HBM theme copy (§7)    100M virtual cash
```

- Matches current architecture: one Run = one version = one account = one universe (D-012, D-017, D-082, D-119).
- Same instrument can run simultaneously under different models in different Runs (`positions` UNIQUE `(strategy_run_id, instrument_id)` is per run).
- A/B comparison already exists (`compareRuns`, [120](120_PERFORMANCE_ANALYTICS.md), UI-5 comparison).
- Least schema change.
- Cost: capital is not shared; the user cannot express "one account, several models".

### 6.2 Option B — One Run, Multiple Model Assignments

```text
one virtual account
  005930          → A
  HBM theme       → B
  Defense theme   → C
```

| Concern | Impact |
| --- | --- |
| Shared cash / risk | BUY allocation (`10% of current cash` at fill) becomes order-dependent across models; fills by `evaluation_id ASC` would decide which model gets cash |
| Model conflict | An instrument in two assigned themes gets two decisions in one slot — precedence rule needed |
| Theme overlap | Same as above; also position UNIQUE `(run, instrument)` forbids two models holding one instrument |
| Assignment precedence | New ordered assignment table, immutable after READY |
| Audit | Every evaluation, order, and fill must record the model version; today the version comes from the Run |
| Reproducibility | Depends on assignment snapshot + precedence + cash ordering |
| Attribution | Per-model P&L requires splitting a shared ledger; MDD per model is not well defined on a shared equity curve |
| Schema | `strategy_runs.strategy_version_id` stops being the single source; N:N assignment + per-row version references |

### 6.3 Decision (HUMAN APPROVED — §36 #2)

First Intraday MVP: **Option A, Run per Model.** Each Run owns one StrategyVersion and an independent virtual account, cash, and performance history. The same instrument may be tested under multiple models by placing it in separate Runs. It satisfies the stated requirement (stocks with A, theme with B, another Run with C) and the A/B comparison use case (§26) without breaking the Run = account invariant. Shared-account multi-model routing (Option B) is **DEFERRED**.

## 7. Theme Semantics

CURRENT VERIFIED: themes are live watchlists; "Add From Theme" copies member ids into `strategy_run_instruments` while DRAFT; later theme edits never change a Run universe (D-132, D-133). The copy keeps no theme provenance, and theme membership has no history (§2 #23, #24).

Theme boundary (HUMAN APPROVED — §36 #14; preserves current D-133):

```text
DRAFT:  user picks Theme "HBM" for Run B
        → members copied into the Run universe now (existing D-133 behavior)
        → provenance captured at the SAME copy instant (new concept):
            source theme id, theme name at copy, copied member set, copied_at
READY:  Run universe immutable (D-119); provenance record immutable with it
        READY does NOT re-resolve the live Theme
later:  Theme edits change only the live watchlist (D-132)
```

- The Run's tradable universe is the DRAFT copy, exactly as today. READY freezes it; it never silently re-reads a newer theme membership.
- A theme-derived feature basket (§9) uses a provenance record whose member set is captured at the same instant and from the same theme state as the universe copy, so the traded universe and the feature basket cannot silently diverge. Theme-scope features never read the live `theme_instruments`.
- If a feature basket legitimately differs from the tradable universe (trade 005930, use HBM breadth as a feature), the basket is its own explicitly captured, immutable provenance record with its own source theme id and capture point, frozen no later than READY.
- If DRAFT edits remove copied members from the universe, the provenance record must state whether the basket still means "theme as copied" or "theme ∩ universe" (OPEN, 12-C).
- Changing membership after READY means a new Run (same rule as universe and policy today).

Relationship to D-133: nothing above changes D-133. The Human decision (§36 #14) keeps the DRAFT-copy snapshot: READY does **not** re-resolve the live Theme, and future Theme edits never rewrite an existing Run. Superseding D-133 with READY-time resolution is not adopted.

OPEN (outside #14; would touch DAILY, out of scope here): whether today's DAILY "Add From Theme" should also start recording provenance.

## 8. Concept Separation (PROPOSED)

```text
Feature Observation → Factor → Strategy / Model Version → Decision
  → [Exit Policy] → Risk Gate (incl. pending exposure, §20.1) → Position Sizing → Execution Policy
```

`[Exit Policy]` is a conditional stage that acts only when stop-loss / take-profit is enabled for the Run (§22; capability supported, default OFF, §36 #7). Each arrow is a separate stage with its own evidence. Current deployed capital (portfolio state) is never automatically a predictive market factor (§9, §10).

| Concept | Question it answers | Owner | Versioned / pinned by |
| --- | --- | --- | --- |
| FEATURE / OBSERVATION | What was observed, when, from which source? | Market-data / observation layer | source + series identity, timestamps (§11, §13) |
| FACTOR | Deterministic transform + normalization of observations | Factor engine | factor code + calculation version (D-069, D-072) |
| MODEL (STRATEGY VERSION) | Should I buy / sell? (factor selection, weights, gates, thresholds, rules) | Strategy engine | strategy version (ACTIVE immutable) |
| DECISION | BUY / HOLD / SELL / NO_ACTION for one instrument at one slot | Strategy engine output | immutable evaluation snapshot (D-078) |
| EXIT POLICY (conditional) | Must an open position be closed regardless of the model (stop-loss / take-profit)? | New exit layer, reads position / entry state | Run-level immutable exit-policy snapshot (§22) |
| RISK GATE | Am I allowed to act on this decision now (incl. open + pending exposure)? | New risk layer | Run-level immutable risk-policy snapshot |
| POSITION SIZING | How much virtual capital? | Paper policy (exists: allocation rate) | Run-level policy snapshot |
| EXECUTION POLICY | When / at what price does the paper order fill? | Paper engine | `paper_trading_policies.execution_price_policy` |

These stay separate stages with separate audit records. They are not folded into one score.

## 9. Feature Scope Taxonomy (PROPOSED)

| Scope | Examples | Keyed by | Notes |
| --- | --- | --- | --- |
| GLOBAL / MACRO | US Treasury yields, global risk indicator | series id | Not per instrument |
| FX / MARKET | USD/KRW, KOSPI200 index / futures price and volume, market breadth | series id or market id | Not per instrument |
| INSTRUMENT | 15M momentum, 15M volume ratio, MA, volatility | instrument | Same shape as today's factors, new timeframe |
| THEME | theme 15M return, breadth, volume ratio | Run theme provenance record (§7) | Aggregated from member instrument bars |
| PORTFOLIO / ACCOUNT STATE | deployed capital, cash ratio, position count, session P/L | Run | **Risk / sizing input, not a directional factor** |

Default separation:

```text
"Should I buy?"          → MODEL   (directional market factors)
"Am I allowed to buy?"   → RISK GATE (portfolio / account state)
"How much?"              → POSITION SIZING
```

A strategy version that wants portfolio state as a model input would need an explicit future capability and Human decision (§36 #15; DEFERRED from the MVP). Accidental coupling — e.g., a factor that silently reads the Run's cash — is forbidden.

## 10. "Leverage / Invested Amount" Clarification

| Meaning | Category |
| --- | --- |
| Market-wide leverage / margin statistic from an external source (e.g., a published credit-balance series) | MARKET FEATURE (observation with its own publication lag) |
| This BJStock Run's deployed capital / invested amount | PORTFOLIO / RISK STATE |

HUMAN APPROVED (§36 #15): these are different and not interchangeable. External market leverage / margin / positioning data may be a MARKET FEATURE; BJStock virtual-account deployed capital is PORTFOLIO / RISK STATE. Portfolio state is not automatically a directional factor; using it as a model input would require an explicit future capability and decision. The source and publication timing of any external leverage series remain unverified (§14, 12-B1).

## 11. Time Model (PROPOSED)

Intraday slot: a canonical label `slot_at` on a 15-minute grid in the market time zone (Asia/Seoul for KRX). `slot_at` is the **end** of the most recent bar the slot may use; it is a grid label, not the instant at which knowledge is frozen. A bar ending at `slot_at` can only be received **after** `slot_at`, so the knowledge cutoff must be a separate, later instant.

```text
bar 09:00–09:15       bar_start_at = 09:00   bar_end_at = 09:15
slot_at               09:15                  grid label; bar_end_at <= slot_at
decision_cutoff_at    09:15 + grace          eligible input set frozen here
decision_at           >= decision_cutoff_at  deterministic decision finalized
order_created_at      >= decision_at         virtual order exists (if any)
decision_deadline_at  >= decision_cutoff_at  latest allowed decision / order instant
                      <  next_slot_at        never reaches the next slot's window
```

Ordering: `slot_at <= decision_cutoff_at <= decision_at <= order_created_at`, and `decision_at`, `order_created_at` `<= decision_deadline_at` for an on-time slot.

Hard Human rule (HUMAN APPROVED — §36 #4) for INTRADAY_15M:

```text
decision_deadline_at < next_slot_at        (next_slot_at = the following 15-minute slot_at)
```

A slot may never consume or overlap the next 15-minute slot's decision window. If the required accurate data, the deterministic decision, or the virtual order cannot be produced before `decision_deadline_at`: no trade, no manufactured historical decision, no historical price; the slot outcome and failure evidence are recorded (§0.2, §29), and operation proceeds only from a current / future valid slot, which starts independently. This prevents cascading delayed slots.

Conceptual timestamps:

| Name | Applies to | Meaning |
| --- | --- | --- |
| `slot_at` | slot | Canonical 15-minute grid label (e.g. 10:30). Identity of the slot (§28). Every bar used satisfies `bar_end_at <= slot_at` |
| `decision_cutoff_at` | slot | Immutable maximum knowledge cutoff: `slot_at + approved grace`. Only inputs with `available_at <= decision_cutoff_at` are eligible. Persisted with the evaluation evidence (§32) |
| `decision_deadline_at` | slot | Bounded latest instant by which the decision (and any order) must exist: `decision_cutoff_at + approved lateness allowance`, always `< next_slot_at`. Beyond it the slot has no trade and its outcome is recorded as `LATE` / `MISSED` / `FAILED` per the final vocabulary (§29) |
| `decision_at` | evaluation | The instant the deterministic model decision is finalized. **This is the timestamp that means "the trading decision now exists."** Recorded |
| `order_created_at` | order | The instant the virtual order comes into existence, if one is created (today `orders.created_at`). Recorded and auditable. Strict lower bound for execution price time in INTRADAY live forward execution only (§21; not applied to DAILY) |
| `bar_start_at`, `bar_end_at` | 15M bar | Interval covered `[start, end)` |
| `source_timestamp` / `effective_at` | external observation | Time the provider says the value refers to |
| `available_at` | any input | Earliest time BJStock may treat the value as known |
| `received_at` | any input | BJStock wall-clock receipt time |
| `evaluated_at` | operation | Optional operational timestamp (start / end of computation). It never means "decision exists", never widens the cutoff, and never anchors execution |
| `executed_at` | execution | Simulated market instant of the fill price (§21); for INTRADAY live forward execution always strictly after `order_created_at` |

Deterministic evaluation start rule (PROPOSED for the Intraday MVP):

```text
slot_at
  → wait until decision_cutoff_at        (preparation before it is allowed; finalizing is not)
  → freeze the eligible observation set: exact versions with available_at <= decision_cutoff_at
  → evaluate the deterministic model on the frozen set only
  → record decision_at                   (decision_at >= decision_cutoff_at)
  → risk / sizing → record order_created_at (if an order is created)
```

Values arriving after `decision_cutoff_at` are never added to the frozen set, even if computation is still running. Live evaluation therefore uses exactly the set a replay would be allowed to see; the knowledge boundary is not `min(evaluated_at, cutoff)`.

Ownership: the approved grace (`decision_cutoff_at - slot_at`) and the approved lateness allowance (`decision_deadline_at - decision_cutoff_at`) are pinned in an **immutable Run-level runtime / execution policy snapshot** created at READY (same lifecycle as `paper_trading_policies`, D-091, D-094). The snapshot also pins the cutoff rule, the `LATE` / `MISSED` / `FAILED` policy, and the execution policy reference. They never change silently during a Run; a change means a new Run or a future approved policy lifecycle defined in 12-C. Ownership is HUMAN APPROVED (§36 #4); numeric values are **OPEN** until 12-B1 / 12-B2 latency evidence, and any value must keep `decision_deadline_at < next_slot_at`. No value is chosen here.

Storage remains UTC (D-010, D-028); display Asia/Seoul. Timestamps are `Instant`s, not local strings.

## 12. Point-in-Time / Look-Ahead Contract (PROPOSED)

For a slot with grid label `slot_at` and knowledge cutoff `decision_cutoff_at` (§11), an input may influence the result only if:

```text
available_at <= decision_cutoff_at
```

For 15M bars additionally:

```text
bar_end_at <= slot_at   and   bar is confirmed closed   and   available_at <= decision_cutoff_at
```

The predicate is **not** `available_at <= slot_at`: a bar ending at `slot_at` is necessarily received after `slot_at`, so that rule would forbid the very bar the slot exists to use (and would silently lag every model by one bar).

Example, `slot_at = 10:30`, `decision_cutoff_at = 10:30 + g` (g OPEN):

| Input | Allowed? |
| --- | --- |
| Stock 10:15–10:30 bar, confirmed, received at 10:30 + δ with δ <= g | Yes |
| Stock 10:15–10:30 bar received after `decision_cutoff_at` | No — MISSING for this slot |
| Latest USD/KRW observation with `available_at <= decision_cutoff_at` | Yes |
| Latest US10Y observation with `available_at <= decision_cutoff_at` | Yes |
| Any value with `available_at > decision_cutoff_at` | No |
| A revised value first available after the cutoff | No — the value as known at the cutoff is used |
| The in-progress 10:30–10:45 bar | No (incomplete; `bar_end_at > slot_at`) |

Invariants:

- No incomplete bar; no observation whose `effective_at` is after `slot_at`; no value received after `decision_cutoff_at`.
- A late-arriving observation is **unavailable (MISSING)** for that slot. It may be used by a later slot whose cutoff it precedes. Any lateness exception requires an explicitly approved, versioned lateness policy (none is proposed).
- The eligible input set is frozen at `decision_cutoff_at` (§11 start rule). Neither `decision_at` nor any operational `evaluated_at` extends the cutoff. A decision that cannot exist by `decision_deadline_at` (always `< next_slot_at`) produces no trade and follows §29 (`LATE` / `MISSED` / `FAILED`).
- `decision_cutoff_at`, `decision_deadline_at`, `decision_at`, and (if an order is created) `order_created_at` are persisted conceptually with each slot evaluation so the knowledge boundary and decision timing are auditable (§32).

For live operation, `available_at` is conservatively `max(provider timestamp semantics, received_at)` — a value BJStock had not received by the cutoff cannot be used even if the provider published it earlier. The existing daily guards (`tradeDate <= asOfDate`, future-bar poison protection, D-064, D-125) are the precedent.

Replay rule: the cutoff is an **eligibility boundary**; the recorded observation references are the **evidence**. Replay of a live evaluation does **not** re-query "all values available by `decision_cutoff_at`" (that could select a richer or different set than live used). It uses:

- the recorded `slot_at` and `decision_cutoff_at`;
- the recorded **exact observation versions** actually selected for that decision (§15);
- the recorded factor definitions / calculation versions and strategy (model) version;
- the recorded risk-policy, runtime / execution-policy, and paper-policy snapshots.

Replay therefore never gains observations the live decision did not use. A future historical analytical replay of slots that never ran live (§29, DEFERRED) has no live evidence to follow; it must apply the same cutoff predicate with a declared conservative latency per source (OPEN), must not use a looser cutoff than live operation would have had, and is labelled as analytical, never as live evidence.

Bar / observation revisions: the exact observation version used by a decision stays the decision's evidence and must remain addressable (§15). A later provider correction never rewrites a past evaluation (same spirit as D-078).

Device clock: slot timing depends on the device / host wall clock. **OPEN:** whether to record clock source / drift evidence.

## 13. Source Timestamps and Staleness (PROPOSED)

Sources update on different cadences. Rule:

```text
value used at slot = latest observation with available_at <= decision_cutoff_at
                     and effective_at <= slot_at
                     and (slot_at - effective_at) <= max_age(source)
otherwise          = MISSING (never zero, never invented)
```

- HUMAN APPROVED (§36 #10): staleness is **source / feature specific**. There is no single global `max_age`; each required source / factor may have its own approved `max_age` based on natural update cadence, provider semantics, and 12-B1 / 12-B2 evidence.
- `max_age` is part of the strategy version's immutable configuration (per input), so a model's tolerance is reproducible. **OPEN:** values per source.
- Each evaluation detail records the observation reference, its `effective_at`, `available_at`, and computed age.
- Carrying the last value forward is allowed **only** within `max_age` and is recorded as such. No interpolation, no synthetic boundary values, no substitution of newer / future data.
- Data beyond `max_age` is MISSING / unusable and follows existing policy: no zero substitution, no weight renormalization (D-065, D-075). Fail-Closed (§0.2): no automatic BUY / SELL from an incomplete required model input set; the audit reason is retained.

## 14. Provider Capability Matrix

No provider capability below is claimed beyond repository evidence. "Candidate" means a class of provider, not a chosen vendor.

| Input | Required frequency | Timestamp semantics needed | Existing repository support | Status | Candidate provider class | Rate-limit concern | Retention need | Open question |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Korean stock 15M OHLCV | every 15 min during session, per instrument | `bar_start_at`, `bar_end_at`, confirmed flag | None (daily `D` and current quote only) | **UNVERIFIED** | KIS quotations (minute chart endpoint not in repo), or other licensed feed | Unknown; repo knows only `EGW00201` throttle and 100 ms / 500 ms pacing (`KisRequestPolicy`), not a published limit | Per Run universe, long-term | Does KIS provide confirmed 15M (or 1M to aggregate) bars? Adjusted or unadjusted? History depth? |
| Korean stock current quote | on demand | quote time | `inquire-price` exists; `stck_bsop_date` optional, no intraday timestamp mapped | VERIFIED (endpoint), UNVERIFIED (timestamp) | KIS | as above | Not persisted today (D-051) | Usable as fill / staleness evidence? |
| USD/KRW | ≥ 15 min, or declared lower cadence | `effective_at`, `available_at` | None | **UNVERIFIED** | FX data provider / central bank reference rate (daily) | Unknown | Long-term | Intraday vs daily reference rate? |
| KOSPI200 futures price | 15M | bar times | None | **UNVERIFIED** | Domestic derivatives quotes (provider TBD) | Unknown | Long-term | Contract roll handling (front month)? |
| Futures volume | 15M | bar times | None | **UNVERIFIED** | Same | Unknown | Long-term | Volume per contract vs continuous series? |
| US Treasury yields | as available | `effective_at` (US session), publication time | None | **UNVERIFIED** | Public end-of-day series or licensed intraday feed | Unknown | Long-term | If only end-of-day: value at 10:30 KST = previous US close; acceptable? |
| Overseas index / futures signal | as available | provider time zone, DST | None | **UNVERIFIED** | Provider TBD | Unknown | Long-term | Which instruments? |
| Market breadth (advancers / decliners) | 15M | slot aligned | None | **UNVERIFIED** | Provider aggregate, or derive from whole-market bars (see §33 — not feasible by default) | High if derived | Long-term | Provider-supplied breadth available? |
| Market leverage / margin statistic | daily or slower | publication lag | None | **UNVERIFIED** | Exchange / association publication | Low | Long-term | See §10 |
| Theme aggregation | 15M | derived from member bars | Theme tables exist; no intraday bars | Derivable once 15M bars exist | Internal computation | Proportional to theme size | Same as bars | Equal vs value weighting (§17) |
| Trading calendar / session times | daily | session open / close, half days | None (daily infers trading days from bars) | **UNVERIFIED** | Exchange calendar source | Low | Long-term | Source of holidays and special sessions |

Provider architecture (HUMAN APPROVED — §36 #9): source-specific; no single provider is required for every feature. KIS is investigated first for Korean stock intraday quotation capability and applicable Korean futures quotations; other provider(s) may be required for USD/KRW, US Treasury, overseas futures / index signals, and any unsupported feature. 12-B1 verifies per source: endpoint / capability, read-only boundary, timestamp semantics, cadence, history availability, rate limits / pacing, licensing / terms, cost, credential custody, and retention constraints. No provider is approved merely because it is listed as a candidate. No KIS `/trading/` endpoint.

No web or API integration is performed in this Gate.

## 15. Intraday Market Bar Domain (PROPOSED)

A separate intraday bar domain, not an overload of `market_daily_bars`:

| Concept field | Meaning |
| --- | --- |
| instrument | FK to `instruments` (existing master, D-130) |
| interval | `15M` first; closed vocabulary, extensible later (e.g., `1M`, `5M`, `30M`) |
| `bar_start_at`, `bar_end_at` | UTC instants; `end - start = interval` |
| open, high, low, close, volume, trading value | Long won / Long volume (D-027), OHLC validation like `DailyBarValidator` |
| price basis | HUMAN APPROVED (§36 #16): the provider's verified raw / native current-market basis, declared explicitly; never assumed for a field until 12-B1 evidence confirms it. The DAILY store stays ADJUSTED (D-047) and is not mixed into intraday |
| source | e.g., `KIS` |
| `received_at` | BJStock receipt time (analogous to `collected_at`) |
| confirmation | Confirmed only when `bar_end_at` has passed and the provider marks / implies completion (OPEN: provider semantics) |
| identity | logical bar `(instrument, interval, bar_start_at, source)`; the stored **version** of that bar must also be addressable (below) |

Revision / immutable replay (PROPOSED; structure chosen in 12-C): an evaluation must reference the exact bar or observation version that existed at its `decision_cutoff_at`. The DAILY store updates bars in place (`MarketDailyBarDao.update`) and is not a precedent for this. Valid conceptual choices:

- revision / version number participates in the observation identity, `(logical bar, revision)`; or
- append-only observation rows, where a correction is a new row linked to the row it supersedes, and earlier rows are never updated.

Either choice satisfies the rule; in-place overwrite of a version already referenced by an evaluation does not. No SQL or entity structure is selected here.

Replay-retention invariant (HUMAN APPROVED — hard rule, §36 #13): any observation version referenced by a retained evaluation must not be silently deleted or overwritten. For an active / non-archived Run, every referenced version stays available. A future retention / archive policy may either:

- A. retain the original immutable observation version; or
- B. move it into an approved replay-preserving archive with stable identity and integrity verification.

It must never leave an evaluation referencing unavailable evidence. If a future policy deliberately degrades full raw replay (e.g., keeping only the values captured in evaluation details), that must be an explicit, approved archive contract, not an accidental effect of retention deletion. Storage budget (§33, §36 #13) cannot override this invariant.

Non-instrument series (FX, futures, yields) use a parallel observation domain keyed by series id rather than fake instruments (§9, §23). Ticks and seconds are **DEFERRED**; the interval vocabulary keeps 15M from being a dead end.

## 16. Factor Timeframe (PROPOSED)

Problem: `MOMENTUM` must never mean daily in one model and 15M in another.

| Placement | Assessment |
| --- | --- |
| Factor definition (code + explicit timeframe attribute) | **Recommended.** Identity is unambiguous; matches today's codes that already embed the window (`MOMENTUM_20D`) |
| Calculation version | Rejected — calc version means formula revision (D-069), not timeframe |
| Strategy configuration only | Rejected — same factor id would mean different things per model |
| Dedicated concept | Unnecessary if the definition carries timeframe |

Rules:

- New intraday factors get distinct codes (e.g., `MOMENTUM_15M`) and timeframe `INTRADAY_15M`; existing six factors are DAILY by definition and unchanged.
- A strategy version declares one evaluation timeframe; activation rejects a factor or signal metric of an incompatible timeframe.
- DAILY context in intraday models (HUMAN APPROVED — §36 #17): an intraday strategy version **may** use slower `DAILY_CONTEXT` inputs (e.g., latest approved daily US Treasury context, prior-session market context, daily macro context), but the timeframe identity stays explicit on the factor definition. Conceptual examples: `US10Y_CONTEXT` (timeframe `DAILY_CONTEXT`), `USD_KRW_INTRADAY` (timeframe `INTRADAY`), `MOMENTUM_15M` (timeframe `INTRADAY_15M`). A `DAILY_CONTEXT` input is never presented as updating every 15 minutes, and every input still obeys point-in-time availability (§12) and its own staleness policy (§13). Exact timeframe vocabulary is decided in 12-C / 12-F.
- A Run's timeframe follows its strategy version; a DAILY version cannot start an intraday Run and vice versa.

## 17. Sample Intraday Factor Catalog (PROPOSED — examples only)

Not final scope. No normalization bounds are declared; bounds require evidence (the daily bounds are themselves a "Phase 4 baseline", [090](090_FACTOR_ENGINE.md)).

| Code | Scope | Definition sketch | Status |
| --- | --- | --- | --- |
| `PRICE_VS_MA_15M` | Instrument | close vs moving average of N confirmed 15M closes (N OPEN) | PROPOSED |
| `MOMENTUM_15M` | Instrument | close / close one bar earlier − 1 | PROPOSED |
| `MOMENTUM_30M` | Instrument | close / close two bars earlier − 1 | PROPOSED |
| `VOLUME_RATIO_15M` | Instrument | bar volume / average of previous N same-interval bars (same time of day vs rolling: OPEN) | PROPOSED |
| `VOLATILITY_15M` | Instrument | stdev of N 15M returns, not annualized | PROPOSED |
| `US10Y_CHANGE` | Macro | latest available yield − reference (prior close? OPEN) | PROPOSED |
| `USD_KRW_CHANGE` | FX | latest available rate vs reference | PROPOSED |
| `KOSPI200_FUTURES_RETURN` | Market | futures 15M return | PROPOSED |
| `KOSPI200_FUTURES_VOLUME_RATIO` | Market | futures 15M volume vs reference | PROPOSED |
| `THEME_RETURN_15M` | Theme | aggregate of member 15M returns over the Run theme provenance record (§7; weighting OPEN) | PROPOSED |
| `THEME_VOLUME_RATIO_15M` | Theme | aggregate member volume vs reference | PROPOSED |
| `THEME_BREADTH_15M` | Theme | share of members with positive 15M return | PROPOSED |

Session-boundary rule (OPEN): whether a window may span the previous session (e.g., `MOMENTUM_30M` at 09:15 using yesterday's last bar) must be explicit per factor.

## 18. Model Evaluation (PROPOSED)

```text
observations (available_at <= decision_cutoff_at, bars with bar_end_at <= slot_at, within max_age)
  → factor raw values (pinned calc versions)
  → normalized scores (0..100, existing scale)
  → enabled weights (exact sum, D-074)
  → gates (min / max, inclusive, D-076)
  → weighted model score
  → signal rules first, then thresholds (D-135, D-136)
  → BUY / HOLD / SELL / NO_ACTION
```

Preserved: exact version pinning, ACTIVE immutability, deterministic BigDecimal math, missing ≠ zero, no silent renormalization (D-075). Any renormalization would require an explicit Human decision and a declared, versioned rule. No AI path.

## 19. Signal Rules (PROPOSED — boundary only)

- `DAILY_CHANGE_PCT` keeps its exact meaning (close_D vs previous trading-day close, [146](146_SIGNAL_RULES.md)) and is never reinterpreted as intraday.
- Intraday rules need new, distinct metric codes carrying timeframe (e.g., a 15M return metric). Example intent: "15M return ≤ −X → BUY", "15M return ≥ +Y → SELL".
- Priority, conflict rejection, DRAFT-only mutation, and rule-before-factor order stay as today (D-134 – D-137).
- A version's rules must match its timeframe. No new metric is implemented here.

## 20. Risk Gate (PROPOSED)

```text
SIGNAL   says BUY / SELL / HOLD
RISK     says PASS / BLOCK(reason)
SIZING   says amount (or zero)
```

CURRENT VERIFIED precedent: `ProcessEvaluationUseCase` already acts as a minimal gate — BUY with an open position → `ORDER_SKIPPED` / `POSITION_ALREADY_OPEN`; SELL without position → `NO_POSITION_TO_SELL`; sizing is `buy_allocation_rate` of cash at fill (`ProcessPendingOrdersUseCase`, `PaperQuantityMath.buyBudgetFromRate`). This gate considers open position quantity only, not pending orders; that is sufficient for DAILY but not for INTRADAY (§20.1).

Candidate policies (architecture candidates, not implemented; the approved control set and numeric-limit ownership are in §36 #6):

| Policy | Notes |
| --- | --- |
| Max deployed capital % | Needs intraday valuation (§25) |
| Max position count | |
| Max amount per instrument | |
| Max amount per theme | Needs Run theme provenance (§7) |
| Daily loss limit | Session P/L from intraday valuation |
| Daily trade-count limit | |
| Cooldown after exit | Minutes / slots |
| Same-day re-entry | HUMAN APPROVED (§36 #6): ALLOWED after full closure — a completely closed position with no unresolved order may re-enter later on the same trading day if a new valid signal occurs |
| Additional buy | HUMAN APPROVED (§36 #6): stays `DISALLOW` for the initial MVP (D-086) |
| Max holding duration | Minutes / sessions |
| New-entry cutoff near close | Slot-based |
| Forced end-of-session liquidation | OPEN; today DAILY never force-liquidates (D-127) |

HUMAN APPROVED (§36 #6): risk controls may include maximum deployed-capital ratio, maximum amount per instrument, maximum position count, maximum theme exposure, daily loss limit, daily trade-count limit, and cooldown. Exact numeric limits are Run policy configuration, not architecture constants. Shape: a Run-level immutable risk-policy snapshot frozen at READY (same pattern as `paper_trading_policies`, D-091, D-094). Audit records the signal decision **and** the risk result **and** the sizing result separately (§32). A BLOCK never erases the BUY decision from evidence.

### 20.1 Pending-Exposure Invariant (HUMAN APPROVED — REQUIRED, non-optional for the Intraday MVP)

CURRENT VERIFIED DAILY behavior: the existing gate looks only at **open position quantity**. `ProcessEvaluationUseCase` skips BUY when `positions.quantity > 0` and SELL when it is 0; it does not look at `PENDING_EXECUTION` orders. The BUY fill path then requires an empty position (`VirtualFillService.executeBuy`: `require(existing.quantity == 0L)`), so a second BUY reaching fill aborts the fill transaction rather than being rejected cleanly.

DAILY normally never meets this case. The pipeline order is Pending Fill → Factor → Evaluation → Order (D-124), and evaluating market date D requires bar D, which is the same bar that fills the D-1 order. So the prior pending fill is normally resolved before the next trading date is evaluated, and the open-position-only guard does not normally see multiple unresolved BUY signals.

INTRADAY breaks that ordering. A prior BUY or SELL may still be `PENDING_EXECUTION` when the next 15M slot runs, for example:

| Cause | Why the order is still pending |
| --- | --- |
| Live execution policy (§21 option A preferred, B fallback candidate) | Fill price must be strictly after `order_created_at`; with B the fill bar is normally later than the next slot's evaluation bar |
| Delayed next bar | Fill bar not yet received by the next slot's cutoff |
| Missed slots (§30) | Gap bars not collected; order waits |
| Session close | Last-slot order waits for the next session |
| Retry after partial completion | Order created; fill step not yet run |

Required financial-safety invariant:

```text
Before creating a new paper order for (run, instrument),
the pre-trade gate considers BOTH
  - current open position exposure, and
  - unresolved (PENDING_EXECUTION / CREATED) paper orders.
No unresolved exposure may lead to a second incompatible order
for the same (run, instrument).
```

MVP rule (HUMAN APPROVED — §36 #6): **at most one unresolved paper order per (run, instrument) at a time.** While one exists, any new BUY or SELL for that pair is not created; the evaluation and decision are kept unchanged, and the skip is audited with a distinct reason (name proposed in 12-C; analogous to `ORDER_SKIPPED` / `POSITION_ALREADY_OPEN`). Whether an opposite-side intent may cancel or replace a pending order is OPEN and not part of the MVP rule.

Ties to the rest of the design:

- Retries: replay of a slot finds the existing order through the evaluation's `client_order_id` (§28) and never adds a second unresolved order.
- Missed slots: pending orders from before a gap follow §30 item 4 (§36 #12); the invariant prevents the resumed slot from stacking new exposure on top.
- Delayed next bar: an order waiting for its fill bar blocks new orders for that pair until filled, rejected, or expired.
- Session close: last-slot orders and their expiry (§21) remain unresolved exposure until resolved.
- Duplicate prevention: complements the existing identity keys; keys stop duplicate rows for the **same** evaluation, the invariant stops incompatible orders from **different** evaluations.

This invariant is evaluated in the same transaction as order creation (D-155 pattern). Nothing is implemented here.

## 21. Execution Policy (HUMAN APPROVED — §36 #3)

Hard live-forward invariant (no retroactive price), scoped to **INTRADAY live forward execution**: a simulated execution price must represent a market observation whose executable timestamp is **strictly after** the virtual order existed, and the complete §11 time ordering must also hold.

```text
execution_price_time > max(decision_cutoff_at, decision_at, order_created_at)
                     = order_created_at          (by the §11 ordering, for an actual order)
```

Scope (DAILY not affected): this strict lower bound applies to INTRADAY live forward execution only. It is **not** applied retroactively to DAILY history. CURRENT VERIFIED: DAILY `orders.created_at` is the wall-clock creation instant (`ProcessEvaluationUseCase`, `createdAt = now()`), while DAILY `executed_at` is the simulated market date at UTC midnight (`VirtualFillService`, `MarketExecutionTime.of(executionDate)`), so a DAILY catch-up fill can legitimately carry `executed_at < created_at`. DAILY keeps its simulated market-date semantics, `NEXT_TRADING_DAY_OPEN`, and its existing valid order / execution history. No generalized invariant, constraint, or test may assert `executed_at > created_at` across all DAILY + INTRADAY rows; 12-C / 12-I / 12-K scope the rule by execution mode / domain.

Consequences:

- Never fill at the just-closed bar's close, and never at a price whose timestamp is at or before `order_created_at`.
- If decision or order creation is delayed, the execution opportunity moves forward. A delay never pulls the fill back to an earlier price.
- A decision that misses `decision_deadline_at` creates no order (§29). There is no compensation at a historical price.

Live forward execution policies (both satisfy the invariant; HUMAN APPROVED, §36 #3):

| Option | Status | Fill price | Notes |
| --- | --- | --- | --- |
| A. First eligible price after the order | **Preferred for the initial MVP** | After the virtual order actually exists, the first provider-verifiable eligible market price strictly after `order_created_at` | Requires a provider with sub-15M executable observations (UNVERIFIED, §14; 12-B1) |
| B. First eligible later bar open | **Approved fallback candidate only** | Open of the first 15M bar whose `bar_start_at` is strictly after `order_created_at` | Used only if provider / runtime constraints make A unavailable or untrustworthy. Switching A → B requires an explicit later Human decision based on 12-B evidence; never a silent switch. Normally bar k+2 relative to the decision bar, so the order stays pending across at least one more slot and §20.1 is mandatory |

```text
example (option B, fallback candidate; option A applies the same strict bound to sub-15M prices)
bar k               09:00–09:15  confirmed, received before the cutoff
slot_at             09:15:00
decision_cutoff_at  09:15:10     (illustrative grace only; value OPEN)
decision_at         09:15:14
order_created_at    09:15:15
09:15 bar open      FORBIDDEN    (price time 09:15:00 <= order_created_at)
09:30 bar open      ALLOWED      (first bar starting strictly after 09:15:15)
a later order_created_at can only move the fill forward, never back to an earlier price
no order for this slot may exist at or after 09:30:00 (decision_deadline_at < next_slot_at, §11)
```

Old optimistic "next-bar open even if already historical": filling at the open of bar k+1 (`bar_start_at = slot_at`) uses a price that existed **before** the decision and order. It is **NOT** a valid live Forward Test option. It may exist only as a separately labelled **OFFLINE ANALYTICAL / OPTIMISTIC RESEARCH REFERENCE**, computed separately, stored apart from Run evidence, and never mixed with or presented as Forward Test performance.

Whatever the live option, the fill is recorded when its fill bar / observation is confirmed, in a PENDING_FILLS-first step like today's DAILY stage. The execution price uses the approved intraday price basis (§36 #16) and its source / provenance is retained as fill evidence (§0.2, §37 F-21).

Trading costs (HUMAN APPROVED — §36 #8): intraday paper performance includes commission, sell tax where applicable, and slippage, reusing existing policy / accounting concepts where valid. `paper_trading_policies.slippage_bps` already exists and is applied at fill (`ProcessPendingOrdersUseCase` via `slippagePolicy.applyToBuy` / `applyToSell`). Slippage shifts prices; it does not repair a timing violation. No Human-unapproved numeric default is fixed in this architecture; exact values come from later provider / trading-environment policy work. Because high-turnover intraday strategies are cost-sensitive, a zero-cost assumption is allowed only as an explicitly labelled simulation scenario, never silently as realistic performance.

Timestamp semantics:

| Field | Proposed meaning |
| --- | --- |
| `order_created_at` | instant the virtual order exists (today `orders.created_at`), linked to its evaluation's `slot_at` / `decision_cutoff_at` / `decision_at` |
| `executed_at` | simulated market instant of the fill price (observation time or bar start), strictly after `order_created_at` (INTRADAY live forward only), not UTC midnight |
| market / session date | KST trading-session date of the fill, explicit or derived by a session-aware rule (below); never the UTC calendar date alone |

Execution-time blast radius (CURRENT VERIFIED): today the trade date is **derived from** `executed_at` as its UTC calendar date (`MarketExecutionTime.toTradeDate`). Consumers: `PerformanceAnalyticsService` (execution rows, closed-trade pairing, `holdingDays = ChronoUnit.DAYS.between(buyDate, sellDate)`) and `PaperTradingLabViewModel` (execution date label). `executions` has no separate market-date column. For intraday `executed_at` this works only by coincidence (the KRX regular session lies inside one UTC date) and same-day round trips report `holdingDays = 0`. Intraday must not assume the UTC calendar date alone is the canonical market-session date. 12-C must choose, for example:

- an explicit market / session date associated with each execution, or
- one canonical, market-session-aware conversion policy used by every consumer.

No schema is selected here. Performance units stay distinct: DAILY `holdingDays` keeps its existing meaning; INTRADAY needs a separate holding-duration metric in minutes / slots / elapsed time (§25). `holdingDays` is never silently reinterpreted.

Expiry ownership: §36 #3 owns ordinary execution, last-slot execution behavior, and ordinary expiry; §36 #12 owns unresolved orders that survive a missed-slot / gap / recovery condition (§30). Last-slot and exact ordinary-expiry mechanics (a decision at the session's last slot, partial-session halts; session parameters per §36 #5) remain for later detailed gate design. Every such choice obeys the no-retroactive invariant: a fill uses only the first eligible price strictly after its own `order_created_at`.

The policy in force is identified by a new `execution_price_policy` value on the Run's immutable policy snapshot (names such as `FIRST_ELIGIBLE_PRICE_AFTER_ORDER` for A and `FIRST_15M_BAR_OPEN_AFTER_ORDER` for B are placeholders), so analytics can tell how `executed_at` must be interpreted.

### 21.1 Full-Slot Trace (HUMAN APPROVED architecture — illustrative; numbers are not decisions)

```text
09:00–09:15 bar closes                         bar_end_at = 09:15:00 <= slot_at
slot_at             09:15:00
decision_cutoff_at  09:15:00 + approved grace   (from the Run runtime / execution policy snapshot)
  at cutoff         freeze exact eligible observation versions (available_at <= cutoff;
                    bars with bar_end_at <= slot_at; within max_age)
                    required input missing / stale / unverifiable → Fail-Closed, no trade (§0.2)
model               deterministic evaluation on the frozen set only (§18)
decision_at         recorded actual instant (>= cutoff)
exit policy         only if enabled for the Run (default OFF, §22)
risk gate           open position + unresolved pending orders for (run, instrument) (§20.1)
position sizing     per Run policy (today: allocation rate of cash at fill)
order_created_at    recorded (<= decision_deadline_at < next_slot_at, i.e. before 09:30:00)
execution           first policy-eligible market price strictly after order_created_at
                    (§21 option A preferred; B only by explicit later Human decision)
audit               exact observation versions, factor definitions / calc versions, strategy version,
                    risk / runtime / execution / paper policy snapshots, price source / provenance,
                    all timestamps above (§0.2, §32)

if decision / order cannot exist by decision_deadline_at
                    → no trade; outcome LATE / MISSED / FAILED recorded with evidence (§29);
                      no retroactive fill; the 09:30 slot starts independently
```

## 22. Stop-Loss / Take-Profit Semantics

CURRENT VERIFIED: BJStock has no stop-loss / take-profit today (no such code in `app/src/main`). `DecisionSource` is only `SIGNAL_RULE` / `FACTOR_STRATEGY`. `positions.average_price` exists.

Stop-loss / take-profit are **not predictive market factors**. They depend on position / entry state (entry price, holding time), which §9 assigns to portfolio / risk state and forbids as an implicit model input. They therefore cannot live inside the weighted model or ordinary signal rules.

Owner when enabled (HUMAN APPROVED — §36 #7):

```text
MODEL DECISION  (BUY / HOLD / SELL / NO_ACTION, from market factors only)
      ↓
EXIT POLICY     (open position only: stop-loss / take-profit / max holding → EXIT or PASS)
      ↓
RISK GATE       (incl. pending exposure, §20.1)
      ↓
POSITION SIZING
      ↓
EXECUTION POLICY (§21)
```

| Aspect | Proposal |
| --- | --- |
| Owner | A dedicated exit-policy stage, separate from model, risk gate, and sizing |
| Precedence | For an open position, an EXIT result overrides model BUY / HOLD and becomes a SELL intent; a model SELL and an EXIT agree. With no open position the stage does nothing. The model decision is still recorded unchanged |
| Risk interaction | An EXIT still passes the pending-exposure invariant (§20.1): no second SELL while one is unresolved. Other risk limits must not block an exit (OPEN) |
| Audit source | A distinct decision source for exits (name decided in 12-C; the existing enum has none), plus the triggering threshold, reference price, and `slot_at` / `decision_cutoff_at` / `decision_at` |
| Policy snapshot | Thresholds / settings live in a Run-level immutable exit-policy snapshot created at READY (same pattern as `paper_trading_policies`), not in the strategy version |

Truthfulness: checks happen only at 15M slots, so this is a **15-minute slot-based exit check** — evaluated on confirmed data under §12, executed under the §21 policy. It is **not** a continuous stop, **not** a tick-level stop, and **not** an exact-level execution (e.g., not "filled at exactly −2.00%"); a price can cross the level and recover inside a bar, or gap far through it. Continuous / tick monitoring is **DEFERRED** and would need a real-time feed (UNVERIFIED, §14). UI and reports must call these "15분 기준 손절/익절" and never imply exact-level fills.

```text
slot observes current loss below the configured threshold
  → EXIT SELL intent → virtual order (subject to §20.1) → valid post-order execution price (§21)
```

MVP status (HUMAN APPROVED — §36 #7): the stop-loss / take-profit capability is **architecturally SUPPORTED but DEFAULT OFF**. If enabled for a Run, the owner, precedence, audit, and snapshot above apply. Initial product default: **OFF**.

## 23. Current Schema Impact Matrix

| Component | Current key / time model (CURRENT VERIFIED) | Works unchanged for INTRADAY? | Why | Proposed conceptual change |
| --- | --- | --- | --- | --- |
| `market_daily_bars` | `(instrument_id, trade_date)` | DAILY yes; intraday no | No time-of-day | Keep; add separate intraday bar domain (§15) |
| `factor_definitions` | `factor_code` unique; no timeframe | Partially | Codes could be added, but timeframe implicit | Add explicit timeframe / scope attribute; DAILY implied for existing rows |
| `factor_values` | `(instrument, factor, evaluation_date, calc_version)`, instrument NOT NULL | No | Day key; no non-instrument scope | Intraday values keyed by slot and scope; or compute-on-evaluation with values captured only in details (12-C decision) |
| `strategies` | `strategy_code` | Yes | Model family | None |
| `strategy_versions` | `(strategy_id, version_no)`, thresholds, status | Mostly | No timeframe / staleness policy | Add immutable evaluation timeframe and per-input max-age |
| `strategy_factor_weights` | `(version, factor)`, weight, gates, pinned calc version | Yes | Factor id identifies timeframe once §16 holds | Validation: same timeframe as version |
| `strategy_signal_rules` | `(version, rule_code)`, metric enum `DAILY_CHANGE_PCT` | Structure yes; metrics no | Daily-only metric | New intraday metric codes |
| `strategy_runs` | one version, date range, initial cash | Yes | Account + version binding | Timeframe follows version; session window config maybe in policy |
| `strategy_run_instruments` | `(run, instrument)`, immutable after READY | Universe semantics yes; provenance no | Universe snapshot copied while DRAFT (D-133) | Theme provenance captured at copy time, either here or in a linked provenance record (§7, 12-C) |
| `forward_test_cycles` | `(run, market_date)`, daily stages, catch-up ASC | No | One per day; catch-up semantics wrong for live intraday | Keep for DAILY; new intraday slot record `(run, interval, slot_at)` with its own outcome vocabulary (EXECUTED / MISSED / LATE / BLOCKED) |
| `stock_evaluations` | `(run, instrument, evaluation_date)` | No | One per day | Either generalize with slot identity or add an intraday evaluation entity. `orders.evaluation_id` FK favors generalizing (12-C decision). Generalizing must not use a nullable slot column inside the UNIQUE key with NULL meaning DAILY (§3); DAILY duplicate protection must stay intact. Evaluation records `slot_at`, `decision_cutoff_at`, `decision_deadline_at`, and `decision_at` (§11) |
| `stock_evaluation_details` | `(evaluation_id, factor_id)` | Yes | Per evaluation, any factor scope | Add observation reference / age fields for point-in-time evidence |
| `orders` | `client_order_id = paper-run-<run>-eval-<eval>-<side>` | Identity yes; pre-trade rule no | Evaluation-based identity is slot-safe if evaluations are per slot; but the creation guard checks open position only, and the BUY fill path requires an empty position | Slot link through evaluation; new pre-trade pending-exposure invariant (§20.1) before order creation; for INTRADAY orders `created_at` serves as auditable `order_created_at`, the strict execution lower bound for INTRADAY live forward execution only (§21; DAILY rows keep current semantics) |
| `executions` | `execution_key = paper:order:<id>:fill:1`; `executed_at` UTC midnight; trade date derived from `executed_at` UTC date | Identity yes; time no | Time-of-day lost; consumers derive date from UTC (§21) | INTRADAY `executed_at` = simulated fill instant per policy, strictly after `order_created_at` (scoped by execution mode; DAILY unchanged, §21); explicit session date or session-aware conversion (12-C) |
| `positions` | `(run, instrument)` projection | Yes | Per run | None |
| `cash_ledger` | `event_key` execution-based; `event_date` LocalDate; balance chain | Yes | Ordering by id; keys execution-based | None (event_date = KST trading date of fill) |
| `paper_trading_policies` | 1:1 run, `NEXT_TRADING_DAY_OPEN` only, additional buy `DISALLOW` | Structure yes; values no | Enum values daily-only | New execution policy value (§21); re-entry / additional-buy values; risk-policy companion snapshot; runtime / execution-policy companion snapshot pinning grace and lateness allowance (§11); exit-policy companion when stops are enabled for the Run (default OFF, §22); existing cost fields and `slippage_bps` reusable (§36 #8) |
| `portfolio_daily_snapshots` | `(run, snapshot_date)`, exact daily close | DAILY summary yes | One per day | Keep one per session for intraday Runs (valuation on the approved intraday price basis, §36 #16; exact field after 12-B1); add intraday valuation series `(run, slot_at)` |
| `trade_audit_logs` | `event_key`, `market_date`, decision source | Mostly | No slot reference; no risk / sizing events | Slot via evaluation; new event types for risk result and sizing (proposed names in 12-C) |
| `forward_operations` | `operation_key`, `trigger`, `operation_kind`, `through_date` NOT NULL, daily `schedule_instance_id` | No as-is | Daily kinds and slot format | New operation kind and intraday slot instance format; `through_date` semantics for intraday OPEN |
| `operational_events` | `event_key`, operation FK | Yes | Generic | New event types (slot started / missed / late) |
| Performance analytics | daily snapshots; calendar-day holding; trade date from `executed_at` UTC date | DAILY yes | Day granularity; `holdingDays` via `ChronoUnit.DAYS` | Session-aware trade date; separate intraday holding-duration metric (minutes / slots); intraday analytics extension (§25). `holdingDays` unchanged |

### 23.1 Classification summary

| Class | Components |
| --- | --- |
| Reusable unchanged (schema, API, and time semantics) | `strategies`, `positions`, `cash_ledger` (`event_date` = KST session date of the fill, already a date) |
| Domain / accounting invariant reusable, but schema / API / time semantics need extension | `orders` (identity reused; new pre-trade pending-exposure invariant, §20.1), `executions` (identity and atomic fill reused; `executed_at` meaning and session date, §21), `strategy_run_instruments` (immutable universe reused; theme provenance, §7), the fill path (D-158 transaction reused; must not rely on the open-position-only guard) |
| Needs timestamp / timeframe generalization (additive, DAILY meaning preserved) | `factor_definitions` (timeframe, scope), `strategy_versions` (timeframe, max-age), `strategy_factor_weights` (validation), `strategy_signal_rules` (new metric codes), `strategy_runs` (timeframe via version), `stock_evaluations` (slot identity with `slot_at`; `decision_cutoff_at` / `decision_deadline_at` / `decision_at` evidence; or new entity; never NULL-as-DAILY in a UNIQUE key, §3), `stock_evaluation_details` (observation version evidence), `paper_trading_policies` (new values), `portfolio_daily_snapshots` (session snapshot for intraday Runs), `trade_audit_logs` (event types), `forward_operations` (kind, slot format), `operational_events` (event types), performance analytics (session-aware dates, intraday holding duration) |
| Genuinely new domain | intraday bars with addressable versions (§15), non-instrument series observations (§9, §15), intraday factor values or equivalent (§23 `factor_values`), per-run intraday slot record (replaces cycle role, §28), Run theme provenance record (§7), Run risk-policy snapshot (§20), Run runtime / execution-policy snapshot with grace and lateness allowance (§11), Run exit-policy snapshot when stops are enabled (§22), intraday valuation series (§25), exchange calendar / session (§31) |
| Unchanged and not used by INTRADAY | `market_daily_bars`, `forward_test_cycles` (DAILY only) |

Incompatibilities confirmed in code: multiple evaluations per instrument per day (UNIQUE in `StockEvaluationEntity`), multiple cycles per run per day (UNIQUE in `ForwardTestCycleEntity`), timestamped factor values (date key in `FactorValueEntity`), intraday execution timestamps and UTC-date trade-date derivation (`MarketExecutionTime.of` / `toTradeDate`), multiple unresolved orders per run / instrument (`ProcessEvaluationUseCase` open-position-only guard; `VirtualFillService.executeBuy` empty-position `require`), intraday valuation (`PortfolioDailySnapshotEntity`), minute holding duration (`ChronoUnit.DAYS`). No migration SQL is written here.

## 24. Reuse of Accounting (PROPOSED)

| Component | Verdict | Change needed |
| --- | --- | --- |
| `orders` | Canonical record, reused | New pre-trade pending-exposure invariant before creation (§20.1); slot link via evaluation |
| `executions` | Canonical record, reused | `executed_at` semantics per execution policy; session date explicit or session-aware (§21) |
| `positions` | Canonical, reused | None |
| `cash_ledger` | Canonical, reused | None; keys already execution-based (D-156) |
| Atomic fill transaction (order + execution + ledger + position + `EXECUTION_FILLED`, D-158) | Reused | Must not be reached by a second unresolved BUY; today that aborts on `require(existing.quantity == 0L)` instead of rejecting (§20.1) |
| Idempotency / conflict codes (D-157, D-159, D-163) | Reused | None |

The financial ledger / accounting source of truth is reusable; no parallel cash / accounting engine is proposed. INTRADAY does, however, require the new pre-trade pending-exposure invariant (§20.1), session-aware execution time (§21), and a new fill-price lookup (15M bar or observation instead of daily bar). The order / fill path is therefore **not** "reused unchanged".

## 25. Performance Analytics Impact (PROPOSED)

- DAILY Performance stays valid and unchanged. Intraday Runs also produce one daily snapshot per session so existing summary, MDD, monthly returns, and UI-5 comparison keep working. Valuation uses the Run's approved intraday price basis (§36 #16: verified raw / native; factor, signal, execution, and valuation bases never silently mixed), so the ADJUSTED daily bar close is not used for intraday-Run valuation; the exact session-close field is chosen after 12-B1 evidence.
- Separate intraday extension (DEFERRED to its own gate): intraday equity curve from slot valuations, holding duration in minutes, trades per session, turnover, intraday MDD, time-of-day performance, fee / tax impact.
- `holdingDays` keeps its DAILY meaning; intraday holding duration (minutes / slots / elapsed time) is a new metric, not a redefinition. Existing trade-date derivation from `executed_at` (§21 blast radius) must be made session-aware before intraday Runs appear in Performance.
- No metric is implemented in this Gate.

## 26. A/B Model Comparison (PROPOSED)

```text
same period, same stored observations
Run A  Model A  100,000,000 virtual cash
Run B  Model B  100,000,000 virtual cash
```

Compare facts: return, MDD, trade count, win rate, holding duration, fees / taxes, and policy context. No automatic winner (consistent with UI-5, [151](151_BJSTOCK_UI_UX_DESIGN.md) §21.7). Both Runs must read the **same** stored observations for a given slot (shared observation store, per-run evaluation), so differences come from the model, not from fetch timing. Each Run remains independently reproducible.

## 27. 15-Minute Orchestration

CURRENT VERIFIED evidence: the DAILY 07:00 one-time WorkManager slot `auto:2026-10-01:0700:KST` first ran at 12:50:55 KST and succeeded on attempt 3 at 13:22:30 KST (Phase 11 B10 delayed-recovery verification). WorkManager gives earliest-eligible timing only (D-168). That is acceptable for a daily catch-up and **not** acceptable for 15-minute slots.

| Criterion | A. Android foreground session runner | B. Always-on external host | C1. Exact alarm per slot (Android) | C2. External recorder + Android evaluator |
| --- | --- | --- | --- | --- |
| Description | User starts a session; foreground service with persistent notification runs slots in-process | PC / server / cloud executes slots; Android monitors / controls | `AlarmManager` exact alarm wakes app each slot | Host only records observations / bars with `received_at`; phone evaluates |
| Timing reliability | Good while service alive; OEM battery policies may still kill | Best | Moderate; Doze limits idle alarms. D-165's "no exact alarms" applies to the DAILY Auto scheduler only; intraday use needs its own decision | Data timing good; evaluation timing as A |
| Network reliability | Phone network (hotspot / mobile / Wi-Fi) | Wired / server network | Phone network | Mixed |
| Battery | Continuous drain during session | None on phone | Lower than A | As A |
| Process death | Possible; needs recovery (Gate 7A pattern) | Host restart handling | Each wake is short-lived | As A |
| Recovery | Missed-slot recording (§30) | Same rules | Same | Same |
| Operational complexity | Low–medium | High (new runtime, deployment, monitoring) | Medium | High |
| Security | KIS secrets stay in Android Keystore (D-029 – D-032) | Secrets move to host; new secret store and threat model | As A | Host needs provider credentials |
| Cost | None | Host cost | None | Host cost |
| Offline behavior | Phone offline → slots MISSED | Phone offline irrelevant | As A | As A for decisions |
| Fit with current architecture | Keeps serverless local app ([000](000_PROJECT_OVERVIEW.md)), Room SoT | Changes the runtime assumption; Room is no longer the SoT or must sync | Needs a new intraday architecture decision (not a DAILY reversal), plus exact-alarm permission | Splits the SoT |

Platform note (UNVERIFIED in this repository; documentary evidence belongs to 12-B1, physical evidence to 12-B2): Android 14+ requires a declared foreground-service type and matching permission; Android 15 introduced time limits for some foreground-service types (e.g., `dataSync`). With `targetSdk = 36`, a full KRX session (§31) must fit the chosen type's rules. CPU sleep during a session may require a wake lock. The manifest currently declares only `INTERNET`: no foreground service, no `FOREGROUND_SERVICE*`, no `WAKE_LOCK`, no exact-alarm permission.

Exact-alarm-based execution (C1) is not forbidden by any current decision. D-165's "no exact alarms" governs the DAILY Auto scheduler. C1 for intraday would require a **new** intraday architecture decision, platform / permission feasibility evidence, and acceptance of exact-alarm permission implications (user-granted or policy-restricted permission on recent Android versions; UNVERIFIED).

Decision (HUMAN APPROVED — §36 #11): **A. Android foreground session runner** is the initial preference and is tested **first**, started explicitly by the user per trading day, with honest missed-slot recording. It would preserve the local runtime, Keystore secrets, and Room as the source of truth. This is **not** an approval that Android is already sufficient: A must pass 12-B1 research, the 12-B2 isolated probe, and Human review of the measured acceptance criteria **before** schema, time-model, or source-of-truth assumptions are frozen in 12-C. If Android passes, candidate A may remain Android-local; if it fails, candidate B (external executor) is reconsidered before 12-C. There is no loyalty to Android-local architecture if measured timing / reliability is inadequate. C2 is DEFERRED. No technology is selected silently; DAILY WorkManager stays as is.

### 27.1 Runtime Acceptance Criteria and Fallback Trigger (HUMAN APPROVED order — values OPEN)

Physical acceptance criteria cannot be measured by research alone, so runtime feasibility is split into two separately reviewable sub-gates.

**12-B1 — Provider / Platform Feasibility Research** (read-only research; no application implementation):

| Area | Evidence needed |
| --- | --- |
| Provider capability | Endpoint / capability availability per §14 (15M or 1M bars, FX, futures, yields, calendar) |
| Provider timestamps | Timestamp semantics, confirmation semantics, expected source cadence |
| Provider limits | Rate limits, licensing, cost, credential and security requirements, custody |
| Service type | Which foreground-service type is permitted for this use with `targetSdk = 36`; declaration / permission / store-policy implications |
| Platform constraints | Continuous-session limits (including any per-day time limit), Doze / wake rules, exact-alarm rules, documented OEM behavior |
| Session / calendar | Availability of an exchange trading calendar and session-time source |
| Fallback implications | What B would change: source of truth, secret custody, sync, cost |

**12-B2 — Isolated Runtime Feasibility Probe** (FUTURE gate; requires its own explicit Human implementation authorization; nothing is implemented in Phase 12-A). It may build only the minimum disposable, isolated measurement probe needed to measure:

| Area | Evidence needed |
| --- | --- |
| Session survival | Full-session foreground survival; screen-on / screen-off behavior; unrecovered process deaths |
| Wake / sleep | Whether slot timers fire on time with the screen off (Doze, wake lock need, OEM battery management on the physical device) |
| Network | Continuity and reconnection behavior on the physical device's real network |
| Provider latency | Real delivery latency per source (input to grace, deadline, and `max_age`) |
| Slot timing | Distribution of `decision_at - slot_at` (probe-simulated, no model); share of expected slots completed before the deadline; worst observed slot delay; maximum consecutive `LATE` / `MISSED` / `FAILED` slots |
| Battery / notification | Battery consumption per session; persistent-notification usability and user burden |

The future 12-B2 authority **must explicitly forbid**: Room business-schema changes; strategy evaluation; BUY / SELL decisions; paper orders; executions; positions; cash ledger; real brokerage calls; KIS `/trading/`; migrations; any production Intraday engine. Probe data must be isolated from the BJStock financial / business source of truth; secret handling and data disposal rules are part of that authority.

Acceptance criteria (shape; numeric thresholds are Human decisions, §36 #11). 12-B2 measures at minimum:

- percentage of expected slots completed before `decision_deadline_at`;
- worst observed slot delay;
- maximum consecutive `LATE` / `MISSED` / `FAILED` slots;
- unrecovered process deaths;
- screen-off survival;
- network reconnection behavior;
- full-session survival;
- battery consumption;
- persistent-notification / user burden.

Provider latency evidence from 12-B1 / 12-B2 is the input for choosing the decision grace, the decision deadline, and per-source `max_age` (§11, §13, §36 #4, #10). No numeric value is selected here.

Runtime decision order:

```text
12-A  Architecture
12-B1 Provider + platform feasibility research
12-B2 Isolated runtime feasibility probe (separate implementation authority)
      → HUMAN runtime-topology decision (§36 #11)
12-C  Time model / schema design — only after that decision
```

Fallback trigger (A → B): if Android-local runtime fails the Human-approved criteria in 12-B2, external-executor architecture (B) must be reconsidered **before** 12-C, so Room / local source-of-truth assumptions are not frozen before runtime feasibility is known. If B is selected, the Room / local source-of-truth assumptions elsewhere in this document (e.g., §24, §34, the 12-C scope in §35) are reopened before 12-C (§37 F-23). A later failure (12-N) reopens 12-C rather than being patched locally.

Engineering item (not a Human product decision): the current single-flight guard (D-152) would block an intraday slot during a DAILY Run Now. Options: separate guards per mode, or a shared guard with priority. Resolved in the orchestration gate (12-J); the Human only accepts the resulting user-visible tradeoff.

## 28. Slot Idempotency (PROPOSED)

| Level | Proposed identity | Prevents |
| --- | --- | --- |
| Session slot instance (operation) | e.g. `i15:<YYYY-MM-DD>:<HHmm>:KST` (format OPEN), operation key `intraday:<slot_instance>:<attempt>` | Two executions of one slot; mirrors `worker:<schedule_instance_id>:<attempt>` (20.9.4) |
| Per-run slot record | `(run, interval, slot_at)` unique | Duplicate slot outcome per Run |
| Evaluation | `(run, instrument, interval, slot_at)` unique; replay → existing row (like `ALREADY_EVALUATED`) | Duplicate evaluation |
| Order | `client_order_id` from evaluation id + side (existing) | Duplicate paper order for the **same** evaluation |
| Unresolved exposure | at most one unresolved order per `(run, instrument)` (MVP rule, §20.1, §36 #6) | Incompatible orders from **different** slots / evaluations, including across retries and gaps |
| Fill | `execution_key` `paper:order:<id>:fill:1` (existing) | Duplicate fill |
| Cash | `cash_ledger.event_key` (existing) | Duplicate cash event |
| Audit / events | deterministic `event_key` (existing pattern) | Duplicate evidence |

Same philosophy as `forward_operations` / `operational_events` (D-145, D-146, D-151, D-165): logical identity is the slot, not the wall-clock attempt or any WorkManager / service id. Keys are not implemented here.

## 29. Failure / Recovery Model (PROPOSED)

| Case | Proposed behavior |
| --- | --- |
| Phone / host offline for a slot | Slot recorded `MISSED` when detected; no retroactive decision |
| Delayed market data | Inputs not available by `decision_cutoff_at` are MISSING for that slot (§12). If the decision / order cannot exist by `decision_deadline_at` (always `< next_slot_at`), there is no order from it and never a fill at a historical price; the outcome is recorded (grace and deadline values OPEN, pinned per Run, §11, §36 #4) |
| External macro source / provider / network unavailable | Input MISSING → enabled factor missing → no decision for affected evaluations (existing missing policy); Fail-Closed with safe provider / error classification recorded (§0.2) |
| One feature stale beyond `max_age` | Same as MISSING; evidence records age |
| One instrument missing a 15M bar | That instrument's evaluation is not produced; others proceed; recorded per instrument |
| Crash mid-slot | Gate 7A pattern: interrupted operation closed on restart; per-step idempotent keys let a retry before `decision_deadline_at` resume on the same frozen input set; after the deadline the slot has no trade and its outcome is recorded. Any order created by the retry still fills only at the first eligible price strictly after its own `order_created_at` (§21) |
| Retry after partial completion | Resume from persisted steps; never re-create rows (keys in §28) |
| Next slot arrives while previous runs | Cannot overlap by rule: `decision_deadline_at < next_slot_at` (§11). The previous slot stops creating orders at its deadline, its outcome is recorded, and the next slot starts independently; two slots for the same Run never run concurrently |

Slot outcome vocabulary (HUMAN APPROVED — §36 #4; final names refined in 12-C):

- `LATE`: the slot started / ran abnormally late but stayed within an explicitly approved recoverable boundary, which never extends past `decision_deadline_at`.
- `MISSED` / `FAILED`: the valid decision / trading window was lost, or required information was not trustworthy / complete.
- No BUY / SELL paper action is ever produced from an invalid slot. Crossing `decision_deadline_at` always means no trade, recorded audit / operational evidence (§0.2), and an independent next slot.

Historical analytical replay vs live forward recovery:

| | Live forward test | Historical replay (future, DEFERRED) |
| --- | --- | --- |
| Purpose | What the model would have done in real time, with real operational limits | What the model would have done if every slot had run |
| Missed slot | Stays missed | Evaluated from stored data |
| Orders / fills | Only from decisions made in time | Simulated, clearly labelled replay |
| Knowledge cutoff | Recorded `decision_cutoff_at` plus the exact observation versions used; replay of a live decision uses those versions only (§12) | Same `decision_cutoff_at` semantics with declared conservative latency; never a looser cutoff (§12) |
| Storage | Run tables | Must not mix with live Run evidence |

Retroactively creating live trades for missed slots at historical prices is forbidden: it would make the forward test look better than reality.

## 30. Missed-Slot Policy (HUMAN APPROVED — §36 #12)

Live Forward Test **never** retroactively reconstructs a missed trade. If the live 10:30 and 10:45 slots are missed and the system recovers at 11:00:

1. Record 10:30 and 10:45 as `MISSED` / `FAILED` evidence with reason (offline / process dead / data late / provider failure).
2. No historical 10:30 / 10:45 trade is created.
3. 11:00 starts a new valid evaluation using only data available by that slot's `decision_cutoff_at`.
4. Optional diagnostic evaluation of missed slots may be stored only as non-executable diagnostics, never as official evaluations, orders, or fills.
5. A pre-existing unresolved order (created **before** the gap) whose first eligible execution opportunity — the first eligible price strictly after its own `order_created_at` under §21 — was lost to the gap is **expired / cancelled** in the initial MVP: it is not executed at a historical price, and it is not suddenly filled at a much later recovery price. A future slot may produce a new decision / order if conditions still warrant it. An order whose first eligible price strictly after its own `order_created_at` was validly observed and recorded is resolved under §21 as usual. Until resolved, an order remains unresolved exposure, so the resumed slot cannot create a second incompatible order for that pair (§20.1).

Expiry ownership: this section and §36 #12 own unresolved orders surviving a missed-slot / gap / recovery condition; ordinary execution, last-slot behavior, and ordinary expiry belong to §21 and §36 #3.

This intentionally differs from DAILY catch-up (D-122), which correctly processes every missed market date because the daily fill is next-day open regardless of wake-up time.

## 31. Market Session / Calendar (PROPOSED)

- HUMAN APPROVED (§36 #5): the initial Intraday MVP trades the **KRX regular session only**; after-hours / extended sessions are **DEFERRED**. An official / verified market calendar and holiday source is required. DAILY infers trading days from bars after the fact; intraday must know in advance (§2 #21). Source selected after 12-B1 evidence.
- KRX regular-session example (commonly published, **UNVERIFIED** in repository): 09:00–15:30 KST with a closing call auction near the end. Under that example, 26 bars of 15M, evaluation slots 09:15 … 15:30. Not hardcoded.
- Session parameters (exact first eligible evaluation slot, exact last new-entry slot, close / auction-bar semantics) are finalized after 12-B1 provider / calendar evidence and are configuration with a source, not code constants. Unverified session / bar semantics are never hardcoded.
- No evaluation outside the session. No 24-hour 15-minute loop.
- Closing behavior (overnight hold vs forced close, last-slot orders) is OPEN (§20, §21).

## 32. Audit / Explainability (PROPOSED)

Per intraday decision, immutable evidence must answer "why BUY / SELL / HOLD at this time". Every expected slot — including HOLD / NO_ACTION, risk blocks, missing / stale data, provider failures, and `LATE` / `MISSED` / `FAILED` slots — leaves an auditable outcome per the HUMAN APPROVED Fail-Closed + Intraday Audit Requirement (§0.2):

| Evidence | Existing home | New |
| --- | --- | --- |
| Run, strategy version | evaluation → run → version | — |
| Instrument, slot timestamps | evaluation | `slot_at`, `decision_cutoff_at`, `decision_deadline_at`, `decision_at` (§11); optional operational `evaluated_at` |
| Policy snapshots in force | Run → `paper_trading_policies` | risk-policy, runtime / execution-policy (grace, lateness allowance), exit-policy if any (§11, §20, §22) |
| Input observation references (exact version) + ages | — | detail fields (§13, §15) |
| Factor raw values, normalized scores, weights, weighted contributions | `stock_evaluation_details` | — |
| Gates, model score, decision, decision source | evaluation, `RULE_TRIGGERED` / `EVALUATION_DECIDED` audit | — |
| Exit-policy result (only if enabled for the Run) | — | exit decision source + trigger (§22) |
| Risk-gate result, incl. pending-exposure skip | `ORDER_SKIPPED` is the only precedent | risk result event / record (§20.1); original model decision preserved |
| Sizing result | computed at fill today | sizing record |
| Order / fill | `orders` (for INTRADAY, `created_at` = `order_created_at`), `executions`, `ORDER_CREATED`, `EXECUTION_FILLED` | execution price time, price source / provenance, proof that `executed_at` > `order_created_at` (INTRADAY live forward), and the execution policy used (§21) |
| No-trade / failure outcome | `operational_events` precedent | slot outcome and reason (HOLD / NO_ACTION, risk block, missing / stale source, provider failure, `LATE` / `MISSED` / `FAILED`) per §0.2 |

Target human trace (future trace / UI should show `decision_at` and `order_created_at`, §37 F-24):

```text
2026-10-15 slot 10:30 KST (cutoff 10:30+g, decision_at <t>, order_created_at <t>)   삼성전자   Model A V7
US10Y        <value>  age <min>   score <s>  weight <w>  contribution <c>
USD/KRW      ...
Futures      ...
15M Momentum ...
Volume Ratio ...
Model Score 78.4   Decision BUY   Risk PASS   Virtual allocation 10,000,000 KRW
Order <client_order_id>   Fill <price> at <executed_at> under <execution_price_policy>
```

Audit rows stay in the same transaction as their business mutation (D-155). Future design extends the existing `trade_audit_logs` / `operational_events` where semantically appropriate rather than inventing a parallel audit subsystem; exact schema ownership is decided in 12-C / 12-K (§0.2).

## 33. Data Volume (estimates)

All numbers are rough, labelled estimates under the §31 example (26 bars / session) and ~250 sessions / year. No provider rate limit is assumed and no provider capability is claimed.

**ASSUMPTION:** the provider supplies native 15-minute bars. This is UNVERIFIED (§14).

| Collection scope | Instruments | 15M bars / year | Rough size / year |
| --- | --- | --- | --- |
| Entire market (current master) | 3,545 | ~23,000,000 | ~2–4 GB incl. indexes (est.) |
| Watchlist / top-N candidates | 100 | ~650,000 | ~100 MB (est.) |
| Selected theme | 30 | ~195,000 | ~30 MB (est.) |
| Per-Run universe (MVP) | 10 | ~65,000 | ~10 MB (est.) |

1-minute variant: if only 1-minute data is available, raw-bar volume is roughly **15×** the table above before aggregation (e.g., ~975,000 raw bars / year for a 10-instrument universe; ~345,000,000 for the entire market), unless raw minutes are aggregated and discarded, which in turn affects replay evidence (§15). Request patterns and pagination would also differ (UNVERIFIED).

Theme baskets: theme-derived features (§7, §9) need bars for **every member of the captured theme basket**, including members outside the tradable Run universe. Collection scope is therefore the union of Run universes **and** theme-basket members, which can exceed the universe sizes above.

Per Run of 10 instruments: ~65,000 evaluations / year; with 8 enabled factors ~520,000 detail rows / year. Macro / FX series: a handful of series × observations at their own cadence — small.

Whole-market 15-minute collection would require thousands of requests per slot and contradicts the spirit of D-062 (no whole-market backfill).

Collection scope (HUMAN APPROVED — §36 #13): the initial MVP does **not** collect the full KOSPI / KOSDAQ universe intraday by default. It collects a small, explicit set — Run universes, Theme-derived frozen universes / baskets, and any other explicitly approved selected set — starting with tens of instruments rather than thousands. Feature-basket members outside the tradable Run universe may be collected when a model requires them, but explicitly and auditably. The exact hard cap is determined from 12-B1 provider limits and measured data volume.

Retention (HUMAN APPROVED — §36 #13): the retention / storage budget is determined later and is subject to the replay-retention invariant (§15). Storage cost / budget must **not** silently delete observation versions required by retained evaluation replay / audit evidence; they are kept, or moved into an approved replay-preserving archive, never purged to meet a budget.

## 34. Initial Intraday MVP Boundary (HUMAN APPROVED)

- PAPER only; KIS `/trading/` stays forbidden; no real orders.
- 15M only; KRX domestic stocks only; KRX regular session only (§36 #5).
- Run per model (§6), small explicit universe of tens of instruments, exact cap after 12-B1 evidence (§36 #13).
- Deterministic weighted strategy version with intraday timeframe; explicit `DAILY_CONTEXT` inputs allowed (§16, §36 #17); no AI execution.
- Inputs: only those whose provider capability is verified in 12-B1 (§36 #9). Instrument and theme factors derived from verified 15M bars first; macro / FX / futures inputs enter when a source is verified — not merely because the architecture supports them.
- Live execution policy: option A preferred, option B an approved fallback candidate only by explicit later Human decision (§21, §36 #3); every INTRADAY fill price strictly after its own `order_created_at` (no retroactive price). Next-bar open exists only as a separately labelled offline optimistic research reference.
- Strict point-in-time evidence: input set frozen at a recorded `decision_cutoff_at`, recorded `decision_at` / `order_created_at`, bounded `decision_deadline_at < next_slot_at`, grace and deadline pinned per Run (§11, §12), and missed-slot honesty (§30).
- Fail-Closed + Intraday Audit Requirement (§0.2): no automatic BUY / SELL without a trustworthy required input set; every expected slot leaves an auditable outcome.
- Source-specific staleness (§13, §36 #10); verified raw / native intraday price basis, never mixed (§36 #16).
- Replay-retention invariant for referenced observation versions (§15).
- Pending-exposure invariant (§20.1): at most one unresolved paper order per `(run, instrument)`; additional buy DISALLOW; same-day re-entry allowed after full closure (§36 #6).
- Stop-loss / take-profit: supported as a 15-minute slot-based EXIT POLICY, default OFF (§22, §36 #7).
- Commission, sell tax, and slippage included; zero cost only as an explicit scenario (§21, §36 #8).
- Independent Run accounts; existing accounting source of truth reused with the §24 extensions (reopened if the §27.1 fallback selects B).
- Auditable decisions (§32).
- Runtime topology only after 12-B1 / 12-B2 evidence meets the Human-approved §27.1 acceptance criteria and the Human runtime-topology decision is made (§36 #11).

DEFERRED: multi-model shared account, tick / continuous stops, after-hours / extended sessions, historical replay, portfolio-state model inputs, whole-market collection, external host (unless §27.1 fallback triggers), intraday analytics beyond basics.

## 35. Proposed Phase 12 Gates

Order adjusted so runtime and provider feasibility evidence precede irreversible design: timestamps, price basis, grace period, and the source of truth all depend on what the provider and the runtime can actually deliver.

| Gate | Scope | Output |
| --- | --- | --- |
| 12-A | Architecture (this document) | Human-approved architecture (2026-10-02); APPROVED / CLOSED |
| 12-B1 | Provider + platform feasibility research (read-only research; no application implementation): provider capability / timestamp semantics / cadence / rate limits / licensing / cost / credentials, Android foreground-service constraints for `targetSdk = 36`, session / calendar source, fallback implications (§14, §27.1) | Verified provider matrix replacing §14 UNVERIFIED rows; documented platform constraints; calendar source; probe design inputs |
| 12-B2 | Isolated runtime feasibility probe (FUTURE; separate explicit Human implementation authority; minimum disposable probe isolated from the business source of truth; no Room business schema, no evaluation, no orders / executions / positions / ledger, no brokerage, no `/trading/`, no migration) (§27.1) | Physical measurements against the §27.1 acceptance-criteria shape; provider latency evidence for grace / deadline / `max_age` |
| — | **Human runtime-topology decision** (§36 #11) | A (Android foreground) or B (external executor); fallback before 12-C if 12-B2 fails |
| 12-C | Time model / schema design (Room + PostgreSQL design review, no runtime change until approved), only after the runtime-topology decision | Entity / key decisions from §23, incl. `decision_cutoff_at` / `decision_deadline_at` / `decision_at` / `order_created_at`, Run runtime / execution-policy snapshot, safe DAILY / INTRADAY keys (§3), observation versions and retention (§15), session date (§21) |
| 12-D | Intraday market data (15M bars for Run universes and theme baskets) | Versioned bars with `received_at`, confirmation |
| 12-E | Feature observation layer (non-instrument series, staleness) | Point-in-time observations |
| 12-F | Intraday factors (timeframe-aware definitions) | Factor catalog v1 for 15M |
| 12-G | Strategy evaluation (version timeframe, slot evaluations, intraday rules) | Immutable slot evaluations with cutoff evidence |
| 12-H | Risk / position sizing (incl. pending-exposure invariant; exit policy capability, default OFF) | Run risk-policy snapshot, audit |
| 12-I | Intraday paper execution (chosen §21 policy, accounting reuse) | Fills with intraday `executed_at` and session date |
| 12-J | Orchestration / recovery (session runner per the Human-approved topology) | Slot operations, single-flight resolution, MISSED / LATE handling |
| 12-K | Audit / replay integrity | Traces, replay with identical cutoff semantics |
| 12-L | Performance analytics | Intraday analytics extension; session-aware dates |
| 12-M | Product UI | Session control, slot status, decision trace |
| 12-N | Physical forward acceptance | On-device session evidence |

Each gate is independently reviewable and requires its own authority. If 12-B2 evidence fails the Human-approved §27.1 criteria, topology is reconsidered before 12-C starts. No gate (12-B1, 12-B2, 12-C, or later) is started by this document.

## 36. Human Decisions (HUMAN APPROVED — 2026-10-02)

Consolidated Human / product decisions, all approved by the Human on 2026-10-02. Approval covers architecture and product direction only; it does not authorize implementation. Items marked "fixed" follow from an approved invariant and are not open; numeric values marked OPEN are decided after the named evidence.

| # | Decision | HUMAN APPROVED position | Sections |
| --- | --- | --- | --- |
| 1 | Model semantics | Initial MVP reuses the existing StrategyVersion; A / B / C Model are product-facing names for independently versioned deterministic StrategyVersions; no separate Model domain for the first MVP; ACTIVE immutability / version pinning stay canonical | §5 |
| 2 | Run topology | Run-per-model: each Run owns one StrategyVersion and an independent virtual account / cash / performance history; the same instrument may be tested under several models in separate Runs; shared-account multi-model routing DEFERRED | §6 |
| 3 | Live execution policy; last-slot behavior; ordinary expiry | Option A preferred: first provider-verifiable eligible price strictly after `order_created_at` (`execution_price_time > order_created_at`, plus the complete §11 ordering; never a price that existed before the order). Option B (first eligible later 15M bar open after `order_created_at`) is an approved fallback candidate only if provider / runtime constraints make A unavailable or untrustworthy; switching requires an explicit later Human decision on 12-B evidence, never silently. Old optimistic next-bar open is not a live option (fixed); offline research reference only, never mixed with Forward Test performance. Owns ordinary execution / last-slot / ordinary expiry; exact mechanics in later gate design | §21 |
| 4 | Decision cutoff / deadline | `decision_cutoff_at = slot_at + approved grace`; grace from 12-B evidence; `decision_deadline_at` immutable per Run via the runtime / execution-policy snapshot at READY. Hard rule: `decision_deadline_at < next_slot_at`. Past the deadline: no trade, no manufactured historical decision, no historical price, outcome and failure evidence recorded, proceed only from a current / future valid slot. `LATE` (within an approved recoverable boundary) vs `MISSED` / `FAILED` (window lost or inputs untrustworthy); no BUY / SELL from an invalid slot. Numeric values OPEN until 12-B evidence | §11, §12, §29 |
| 5 | Market session / calendar | KRX regular session only for the initial MVP; after-hours / extended DEFERRED; official / verified calendar and holiday source required; exact first eligible slot, last new-entry slot, and close / auction-bar semantics finalized after 12-B1; no unverified semantics hardcoded | §20, §31 |
| 6 | Intraday risk | Additional buy stays DISALLOW; the pending-exposure invariant is REQUIRED and non-optional (fixed): an unresolved order blocks a new incompatible order for the same (run, instrument); same-day re-entry ALLOWED after full closure with no unresolved order; risk controls may include deployed-capital ratio, amount per instrument, position count, theme exposure, daily loss limit, daily trade-count limit, cooldown; numeric limits are Run policy configuration frozen at READY | §20, §20.1 |
| 7 | Stop-loss / take-profit | Architecturally supported, default OFF. If enabled: owned by EXIT POLICY, not a predictive factor, reads position / entry state, model decision recorded, may override BUY / HOLD into SELL intent for an open position, subject to §20.1, settings pinned in a Run-level exit-policy snapshot; described only as a 15-minute slot-based exit check, never continuous / tick / exact-level | §22 |
| 8 | Trading costs | Intraday paper performance includes commission, sell tax where applicable, and slippage; reuse existing policy / accounting concepts; no Human-unapproved numeric defaults; zero cost only as an explicitly labelled scenario | §21, §24 |
| 9 | Data providers | Source-specific; multiple providers allowed. KIS investigated first for Korean stock intraday and applicable Korean futures quotations; other providers may be needed for USD/KRW, US Treasury, overseas futures / index, and unsupported features. 12-B1 verifies per source: capability, read-only boundary, timestamp semantics, cadence, history, rate limits / pacing, licensing / terms, cost, credential custody, retention constraints. No provider is approved by being a candidate; no KIS `/trading/` | §14 |
| 10 | Staleness | Source / feature specific `max_age`, never one global value; based on cadence, provider semantics, and 12-B evidence. Beyond `max_age`: MISSING / unusable, no zero score, no newer / future substitution, no weight renormalization, Fail-Closed, no automatic BUY / SELL, audit reason retained | §13, §0.2 |
| 11 | Runtime topology | Android-local foreground execution tested first; not an approval that Android is sufficient. Order: 12-B1 → 12-B2 (separate Human implementation authority) → Human review of measured criteria → Android passes: A may remain local; Android fails: external executor (B) reconsidered before 12-C. No loyalty to Android-local; source of truth not frozen before this decision; criteria values OPEN | §27, §27.1, §35 |
| 12 | Missed slots and gap-surviving orders | Live Forward never reconstructs a missed trade (fixed): missed slots recorded `MISSED` / `FAILED`, no historical trade, recovery starts a new valid evaluation. A pre-existing unresolved order whose first eligible price strictly after its own `order_created_at` (§21) was lost to the gap is expired / cancelled in the initial MVP — never filled at a historical price or suddenly at a much later recovery price. Owns gap / missed-slot / recovery treatment of unresolved orders | §21, §29, §30 |
| 13 | Universe size / retention | No full KOSPI / KOSDAQ intraday collection by default; small explicit sets (Run universe, frozen Theme universe / basket, other approved set), tens of instruments; feature-basket members outside the Run universe only explicitly and auditably; hard cap after 12-B1; budget later. Storage budget never silently deletes observation versions required by retained replay / audit evidence (fixed, §15) | §15, §33, §34 |
| 14 | Theme provenance | Membership copied to the Run universe while DRAFT; exact copied membership and Theme provenance frozen at that copy point; universe immutable after READY; READY does not re-resolve the live Theme; Theme edits never rewrite an existing Run; a separate feature basket needs its own immutable provenance. Preserves D-133 | §7 |
| 15 | "Leverage / invested amount" | External market leverage / margin / positioning data may be a MARKET FEATURE; virtual-account deployed capital is PORTFOLIO / RISK STATE; not interchangeable; portfolio state is not automatically a directional factor; explicit future use as a model input needs its own capability / decision | §9, §10 |
| 16 | Intraday price basis | Intraday live paper execution uses the provider's verified raw / native current-market basis; within one Intraday Run, factor / signal / execution / valuation bases are never silently mixed; field semantics verified in 12-B1, never assumed; DAILY adjusted semantics unchanged | §15, §25 |
| 17 | DAILY context in Intraday models | Intraday StrategyVersions may use slower `DAILY_CONTEXT` features with explicit timeframe identity (e.g., `US10Y_CONTEXT` = `DAILY_CONTEXT`, `USD_KRW_INTRADAY` = `INTRADAY`, `MOMENTUM_15M` = `INTRADAY_15M`); never presented as updating every 15 minutes; every input obeys point-in-time availability and staleness | §16 |

Consolidation from the previous 19-row table: former #4 + #19 → #3; former #8 merged into #5; former #5 + #6 + #9 → #6; former #3 (15M first, extensible vocabulary) is fixed by the requirement and kept as architecture (§15); former #13 reduced to its open part (#12); former #15 reframed against D-133 (#14); former #18 → #16.

Engineering items moved out of the Human product table (resolved in the named gate; the Human only accepts user-visible tradeoffs): DAILY / INTRADAY single-flight mechanism (§27.1, 12-J); numeric `max_age` per source (12-B1 / 12-B2 evidence, 12-C); clock-source / drift evidence (§12); per-factor session-boundary windows and theme aggregation weighting (§17, 12-F); slot-instance key format (§28, 12-C).

### 36.1 Final Human Decision Summary

| # | Decision | Status |
| --- | --- | --- |
| 1 | Model = StrategyVersion | HUMAN APPROVED — 2026-10-02 |
| 2 | Run-per-model | HUMAN APPROVED — 2026-10-02 |
| 3 | Post-order live execution; A preferred, B fallback candidate | HUMAN APPROVED — 2026-10-02 |
| 4 | Cutoff / deadline; `decision_deadline_at < next_slot_at` | HUMAN APPROVED — 2026-10-02 |
| 5 | KRX regular-session MVP | HUMAN APPROVED — 2026-10-02 |
| 6 | Risk + pending exposure + same-day re-entry + no additional buy | HUMAN APPROVED — 2026-10-02 |
| 7 | Optional 15M EXIT POLICY, default OFF | HUMAN APPROVED — 2026-10-02 |
| 8 | Commission / tax / slippage included | HUMAN APPROVED — 2026-10-02 |
| 9 | Multi-provider allowed; KIS-first verification for applicable KR data | HUMAN APPROVED — 2026-10-02 |
| 10 | Source-specific staleness + Fail-Closed | HUMAN APPROVED — 2026-10-02 |
| 11 | Android foreground first probe; external fallback before 12-C | HUMAN APPROVED — 2026-10-02 |
| 12 | Missed slots never reconstructed; stale unresolved order expires | HUMAN APPROVED — 2026-10-02 |
| 13 | Small explicit universe + replay-safe retention | HUMAN APPROVED — 2026-10-02 |
| 14 | DRAFT Theme snapshot / provenance | HUMAN APPROVED — 2026-10-02 |
| 15 | Market leverage vs portfolio deployed capital separated | HUMAN APPROVED — 2026-10-02 |
| 16 | Raw / native verified Intraday price basis; no mixing | HUMAN APPROVED — 2026-10-02 |
| 17 | `DAILY_CONTEXT` allowed with explicit timeframe / availability | HUMAN APPROVED — 2026-10-02 |

## 37. Review Findings Closure and Carry-Forward

Independent review history: Final Independent Delta Re-verification PASS (BLOCKER 0, HIGH 0, MEDIUM 0). Remaining findings are recorded here.

| Finding | Severity | Status | Resolution / carry-forward |
| --- | --- | --- | --- |
| F-18 | LOW | **CLOSED BY HUMAN APPROVAL FINALIZATION** | Execution-eligibility wording uses "first eligible price strictly after its own `order_created_at`" (§21, §29, §30, #12); #12 cites §21; expiry ownership split: #3 ordinary execution / last-slot / ordinary expiry, #12 gap / missed-slot / recovery |
| F-19 | LOW | **CLOSED BY HUMAN APPROVAL FINALIZATION** | `order_created_at` as strict execution lower bound is scoped to INTRADAY live forward execution (§11, §21, §23, §32); not applied to DAILY history; no generalized `executed_at > created_at` invariant / test across DAILY + INTRADAY rows; 12-C / 12-I / 12-K scope by execution mode / domain |
| F-20 | LOW | **CLOSED BY HUMAN APPROVAL FINALIZATION** | Hard Human rule `decision_deadline_at < next_slot_at` for INTRADAY_15M (§11, §29, #4); crossing the deadline → no trade, evidence recorded, next slot independent; numeric values from 12-B evidence |
| F-21 | INFO | Carry-forward (12-C / 12-I / 12-K) | Execution-price observation / provenance must be retained sufficiently to prove the fill and its timing |
| F-22 | INFO | Carry-forward (12-C / 12-I) | Define the exact boundary for pending-fill processing before the Risk Gate and record which portfolio / pending state the gate read |
| F-23 | INFO | Carry-forward (§27.1 decision, before 12-C) | If 12-B2 / the runtime decision selects the external executor, Room / local source-of-truth assumptions (§24, §34, §35 12-C scope) are reopened before 12-C |
| F-24 | INFO | Carry-forward (12-K / 12-M) | Future human trace / UI includes `decision_at` and `order_created_at` (§32) |

INFO items are non-blocking design follow-ups, not open architecture defects.

## 38. Approval Boundary

| Item | Status |
| --- | --- |
| Phase 12-A | **APPROVED / CLOSED** |
| Architecture | **APPROVED** (Human, 2026-10-02) |
| Human Decisions | **APPROVED** (all 17, §36) |
| ImplementationAuthority | **NONE** |
| Next eligible gate | 12-B1 — Provider + Platform Feasibility Research |

12-B1 is **NOT** started or authorized by this document. A separate Human instruction is required. 12-B2, 12-C, and every later gate each require their own separate authority; no implementation (Kotlin, Room / schema, PostgreSQL migration, provider integration, foreground service, runtime probe, scheduler, trading engine, UI) is authorized by this approval.
