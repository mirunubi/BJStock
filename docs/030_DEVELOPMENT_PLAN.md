# BJStock Development Plan

## Roadmap

### Phase 0 — Project Foundation

Git bootstrap, documentation, and Docker PostgreSQL laboratory.

Status: complete.

### Phase 1 — DB Architecture

Design and validate domain schema in Docker PostgreSQL.

Includes:

- 16 business tables in schema `bjstock`
- `schema_migrations` tracking
- TEXT + CHECK vocabularies
- NUMERIC money/score types
- constraint, index, and FK verification
- ERD and data dictionary

Status: complete.

### Phase 2 — Android Foundation

Create the Android app shell. No live trading. Runtime DB will be Room / SQLite.

Status: complete (build / unit verified).

### Phase 2.1 — Android Runtime Gate

Install and smoke-test the debug APK on a physical Android device.

Status: DEFERRED — physical device pending.

This is not a FAIL. Build and unit tests passed. Runtime was not executed because no test device is connected.

### Phase 3-A — KIS Authentication Foundation

Manage KIS App Key / App Secret, Keystore encryption, OAuth token issuance, token cache, and connection status.

No market-data API, account collection, or broker orders.

Status: complete for build / unit verification. Runtime (Keystore on device, live KIS token, Settings UI) is DEFERRED — physical device pending.

### Phase 3-B — KIS Read-Only Market Data

Read-only KIS quotations: current price and daily bars. No Room persistence, no orders.

Status: complete for build / unit verification. Runtime (actual KIS current price / daily price on device) is DEFERRED — physical device pending.

### Phase 3-C — Market Data Persistence

Persist verified KIS daily bars to Room. Current quotes stay UI-only.

Status: complete for build / unit verification. Runtime (actual KIS daily bars into device Room) is DEFERRED — physical device pending.

### Phase 3-D — Instrument Master & Historical Sync

KOSPI/KOSDAQ instrument master from official MST files, and selected-instrument historical daily backfill in 90-day chunks.

Status: complete for build / unit verification. Runtime (actual MST download, live KIS history, Room v1→v2 on device) is DEFERRED — physical device pending.

### Phase 4 — Factor Definition & Calculation Foundation

Define six market-data system factors and compute raw values plus 0..100 scores from Room daily bars.

Status: complete for build / unit verification. Runtime (KIS history on device → Room → Factor Test screen) is DEFERRED — physical device pending.

### Phase 5 — Strategy Weighting & Evaluation

Score instruments and apply strategy weights/versions.

Status: complete for build / JVM-Robolectric verification. Runtime (physical-device KIS → factor → strategy flow) is DEFERRED — physical device pending.

### Phase 6 — Virtual Account / Paper Trading

Implement BJStock-owned virtual account, paper orders, and executions against `strategy_runs`.

Status: complete for build / JVM-Robolectric verification. Runtime (physical-device end-to-end forward paper flow) is DEFERRED — physical device pending.

### Phase 6.1 — Paper Trading Policy Snapshot Hardening

Persist one immutable `paper_trading_policies` snapshot per strategy run so past runs remain reproducible after code defaults change.

Status: complete for build / JVM-Robolectric verification. Runtime remains DEFERRED — physical device pending.

### Phase 7 — Performance Analytics

Track forward-test results through portfolio snapshots and related metrics.

Status: complete for build / JVM-Robolectric verification. Runtime (physical-device Forward Test dashboard) is DEFERRED — physical device pending.

### Phase 8 — AI Advisory

Add optional AI advice. AI remains off-by-default capable and never executes trades.

Status: complete for build / JVM-Robolectric verification. Clipboard/Share/Paste runtime is DEFERRED — physical device pending.

### Phase 9 — Long-Term Forward Test

Run paper trading for one year or longer and review strategy quality.

Status: complete for build / JVM-Robolectric verification. Physical WorkManager + KIS daily automation runtime is DEFERRED — physical device pending.

### Phase 9.1 — Themes, Signal Rules & Audit Logging

Themes/watchlists, DAILY_CHANGE_PCT signal rules, trade audit timeline, and rolling 7-day API error diagnostics.

Status: complete for build / JVM-Robolectric verification. Runtime remains DEFERRED — physical device pending.

### Phase 10 — Physical Device End-to-End Runtime Acceptance

Install and run the app on a physical Android device and accept the end-to-end runtime.

Status: COMPLETE / PASS (closeout baseline `9bbb220`).

### Phase 11 — Operational Reliability

Operational reliability, error handling, audit atomicity and the daily one-time Auto scheduler ([150](150_OPERATIONAL_RELIABILITY_STANDARD.md)).

Status: CLOSED.

### Phase 12 — Intraday Multi-Factor Strategy

Intraday architecture, provider / platform feasibility, security architecture and an isolated on-device probe ([160](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md), [161](161_INTRADAY_PROVIDER_PLATFORM_FEASIBILITY.md), [162](162_INTRADAY_ISOLATED_PROBE_IMPLEMENTATION.md), [163](163_SECURITY_AUDIT_FAILURE_CONTAINMENT_ARCHITECTURE.md)).

Status: IN PROGRESS / PARTIAL.

- B2 Human Gate: CLOSED — PASS — UNATTENDED WITH LIMITATIONS. Scope and limitations are summarized in the [162](162_INTRADAY_ISOLATED_PROBE_IMPLEMENTATION.md) header.
- Stage B-1 / BG-RECONNECT: HOLD / NOT EXECUTED.

Phase 12 is not complete. No intraday strategy runtime is implemented.

### Future — Live Trading

Live broker orders are explicitly out of current scope.

## Current Phase

Current: Phase 12 — runtime validation / reconnect follow-up.

Next runtime gate: Stage B-1 BG-RECONNECT physical validation, only when Human re-authorizes it. This plan grants no implementation or runtime authority.

The "DEFERRED — physical device pending" lines in the Phase 2.1 – 9.1 entries record status at the time they were written. Phase 10 performed the physical-device end-to-end runtime acceptance; those entries were not individually re-marked.

Auto Forward Test remains OFF by default (D-128); Human confirmed the default stays OFF. The reconsideration condition is unattended physical scheduler acceptance, which remains UNPROVEN.
