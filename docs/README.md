# BJStock Documentation

Navigation index only. This file has no authority of its own: each linked document keeps its own status, and Human gate decisions decide results. Documents stay flat in `docs/` with their existing three-digit numbers.

BJStock is paper trading only. Real brokerage orders and KIS `/trading/` are out of scope.

## 1. Start Here

- [000 Project Overview](000_PROJECT_OVERVIEW.md): scope and product boundary.
- [030 Development Plan](030_DEVELOPMENT_PLAN.md): phase roadmap and current phase.
- [010 Architecture](010_ARCHITECTURE.md): daily pipeline and components.
- [020 Data Architecture](020_DATA_ARCHITECTURE.md): data model rationale.
- [060 Decision Log](060_DECISION_LOG.md): numbered decisions. A later entry states which earlier one it supersedes.
- [150 Operational Reliability Standard](150_OPERATIONAL_RELIABILITY_STANDARD.md): error model, audit atomicity, scheduler, operational evidence.
- [140 Forward Test Orchestration](140_FORWARD_TEST_ORCHESTRATION.md): forward-test semantics.
- [141 Forward Test Operations](141_FORWARD_TEST_OPERATIONS.md): Auto / Run Now operation.
- [110 Paper Trading Engine](110_PAPER_TRADING_ENGINE.md): orders, fills, ledger.
- [113 Paper Trading Policy](113_PAPER_TRADING_POLICY.md): per-run policy snapshot.
- [151 UI/UX Design Baseline](151_BJSTOCK_UI_UX_DESIGN.md): UI structure (DRAFT, partly implemented).
- [160 Intraday Multi-Factor Model Architecture](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md): approved intraday direction, not implemented.
- [163 Security / Audit / Failure-Containment Architecture](163_SECURITY_AUDIT_FAILURE_CONTAINMENT_ARCHITECTURE.md): approved as architecture only.
- [164 MA5 Breakout Strategy Requirement](164_MA5_BREAKOUT_VOLUME_CLOSE_ENTRY_STRATEGY_REQUIREMENT.md): first strategy and strategy-template requirement.

## 2. Current Documents by Topic

Grouped for navigation only.

Foundation / Planning:

- [000 Project Overview](000_PROJECT_OVERVIEW.md)
- [010 Architecture](010_ARCHITECTURE.md)
- [030 Development Plan](030_DEVELOPMENT_PLAN.md)
- [050 Local Development](050_LOCAL_DEVELOPMENT.md)
- [060 Decision Log](060_DECISION_LOG.md)
- [070 Android Foundation](070_ANDROID_FOUNDATION.md) (historical, see §5)

Data:

- [020 Data Architecture](020_DATA_ARCHITECTURE.md)
- [021 Data Dictionary](021_DATA_DICTIONARY.md)
- [022 ERD](022_ERD.md)
- [023 Room Schema Mapping](023_ROOM_SCHEMA_MAPPING.md)

Market Data / KIS:

- [080 KIS Authentication](080_KIS_AUTHENTICATION.md)
- [081 KIS Market Data](081_KIS_MARKET_DATA.md)
- [082 Market Data Persistence](082_MARKET_DATA_PERSISTENCE.md)
- [083 Instrument Master](083_INSTRUMENT_MASTER.md)
- [084 Historical Sync](084_HISTORICAL_SYNC.md)

Factor / Strategy:

- [090 Factor Engine](090_FACTOR_ENGINE.md)
- [091 Factor Catalog](091_FACTOR_CATALOG.md)
- [100 Strategy Engine](100_STRATEGY_ENGINE.md)
- [101 Strategy Evaluation](101_STRATEGY_EVALUATION.md)
- [145 Themes and Watchlists](145_THEMES_AND_WATCHLISTS.md)
- [146 Signal Rules](146_SIGNAL_RULES.md)

Paper Trading / Performance:

- [110 Paper Trading Engine](110_PAPER_TRADING_ENGINE.md)
- [111 Virtual Account](111_VIRTUAL_ACCOUNT.md)
- [112 Portfolio Snapshot](112_PORTFOLIO_SNAPSHOT.md)
- [113 Paper Trading Policy](113_PAPER_TRADING_POLICY.md)
- [120 Performance Analytics](120_PERFORMANCE_ANALYTICS.md)
- [121 Forward Test Dashboard](121_FORWARD_TEST_DASHBOARD.md)

AI:

- [040 AI Advisory Policy](040_AI_ADVISORY_POLICY.md)
- [130 AI Advisory](130_AI_ADVISORY.md)
- [131 AI Prompt Contract](131_AI_PROMPT_CONTRACT.md)

Forward Test / Operations:

- [140 Forward Test Orchestration](140_FORWARD_TEST_ORCHESTRATION.md)
- [141 Forward Test Operations](141_FORWARD_TEST_OPERATIONS.md)
- [147 Trade Audit Log](147_TRADE_AUDIT_LOG.md)
- [148 API Error Logging](148_API_ERROR_LOGGING.md)

Reliability / UI:

- [150 Operational Reliability Standard](150_OPERATIONAL_RELIABILITY_STANDARD.md)
- [151 UI/UX Design Baseline](151_BJSTOCK_UI_UX_DESIGN.md)
- [165 UI Navigation / Adaptive Architecture](165_UI_NAVIGATION_ADAPTIVE_ARCHITECTURE.md): NAV-1 adaptive navigation and adaptive screen layouts (physical UI not yet verified)

Intraday / Security / Strategy Requirements:

- [160 Intraday Multi-Factor Model Architecture](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md)
- [161 Intraday Provider / Platform Feasibility](161_INTRADAY_PROVIDER_PLATFORM_FEASIBILITY.md) (historical, see §5)
- [162 Intraday Isolated Probe Implementation](162_INTRADAY_ISOLATED_PROBE_IMPLEMENTATION.md) (validation, see §5)
- [163 Security / Audit / Failure-Containment Architecture](163_SECURITY_AUDIT_FAILURE_CONTAINMENT_ARCHITECTURE.md)
- [164 MA5 Breakout Strategy Requirement](164_MA5_BREAKOUT_VOLUME_CLOSE_ENTRY_STRATEGY_REQUIREMENT.md)

## 3. Requirements

- [040 AI Advisory Policy](040_AI_ADVISORY_POLICY.md)
- [151 UI/UX Design Baseline](151_BJSTOCK_UI_UX_DESIGN.md)
- [164 MA5 Breakout Strategy Requirement](164_MA5_BREAKOUT_VOLUME_CLOSE_ENTRY_STRATEGY_REQUIREMENT.md)

## 4. Architecture / Decisions

- [010 Architecture](010_ARCHITECTURE.md)
- [020 Data Architecture](020_DATA_ARCHITECTURE.md)
- [060 Decision Log](060_DECISION_LOG.md)
- Engine architecture: [090](090_FACTOR_ENGINE.md), [100](100_STRATEGY_ENGINE.md), [101](101_STRATEGY_EVALUATION.md), [110](110_PAPER_TRADING_ENGINE.md), [120](120_PERFORMANCE_ANALYTICS.md), [130](130_AI_ADVISORY.md)
- [140 Forward Test Orchestration](140_FORWARD_TEST_ORCHESTRATION.md)
- [150 Operational Reliability Standard](150_OPERATIONAL_RELIABILITY_STANDARD.md)
- [160 Intraday Multi-Factor Model Architecture](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md)
- [163 Security / Audit / Failure-Containment Architecture](163_SECURITY_AUDIT_FAILURE_CONTAINMENT_ARCHITECTURE.md)
- [165 UI Navigation / Adaptive Architecture](165_UI_NAVIGATION_ADAPTIVE_ARCHITECTURE.md)

## 5. Validation / Historical

These documents are evidence or historical records. Do not read them as current product truth.

- [070 Android Foundation](070_ANDROID_FOUNDATION.md): historical Phase 2 record.
- [161 Intraday Provider / Platform Feasibility](161_INTRADAY_PROVIDER_PLATFORM_FEASIBILITY.md): historical Phase 12-B1 feasibility / gate record. Its "12-B2 NOT STARTED" wording reflects its date.
- [162 Intraday Isolated Probe Implementation](162_INTRADAY_ISOLATED_PROBE_IMPLEMENTATION.md): current Phase 12 validation / evidence document. Its header holds the current status; the body is the implementation and evidence record.

## 6. Current Phase Status

- Phase 10: CLOSED / PASS.
- Phase 11: CLOSED. This is a Human cleanup decision; the detailed historical documents may not contain a formal closeout line.
- Phase 12: IN PROGRESS / PARTIAL.
  - B2 Human Gate: CLOSED — PASS — UNATTENDED WITH LIMITATIONS (scope and limitations in the [162](162_INTRADAY_ISOLATED_PROBE_IMPLEMENTATION.md) header).
  - Stage B-1 / BG-RECONNECT: HOLD / NOT EXECUTED.

See [030 Development Plan](030_DEVELOPMENT_PLAN.md) for the roadmap.

## 7. Pending Branches

- `feature/ui-strategy-admin-demo` at `69bd205`: MERGED INTO MAIN as merge commit `a2031deed497ae1ac793bbd744b2ccd3db193972` (2026-10-11). It adds [docs/165](165_UI_NAVIGATION_ADAPTIVE_ARCHITECTURE.md). Physical UI: NOT YET VERIFIED.

`docs/strategy-template-requirement` (`31266d1`) is not pending: its docs/164 change is on main as `d0d5fa9`. The branch remains only as integration provenance.

## 8. Known Documentation Follow-ups

There are no open documentation follow-ups. The `docs/165` reconciliation after the UI merge is complete (docs/150, docs/151 §21.8, docs/164 §27.1, docs/165 header).
