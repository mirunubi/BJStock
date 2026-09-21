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

KOSPI/KOSDAQ instrument master and multi-call historical daily backfill. Not started.

### Phase 4 — Factor Engine

Define factors and compute factor values from stored market data.

### Phase 5 — Scoring / Strategy Engine

Score instruments and apply strategy weights/versions.

### Phase 6 — Virtual Account / Paper Trading

Implement BJStock-owned virtual account, paper orders, and executions against `strategy_runs`.

### Phase 7 — Performance Analytics

Track forward-test results through portfolio snapshots and related metrics.

### Phase 8 — AI Advisory

Add optional AI advice. AI remains off-by-default capable and never executes trades.

### Phase 9 — Long-Term Forward Test

Run paper trading for one year or longer and review strategy quality.

### Future — Live Trading

Live broker orders are explicitly out of current scope.

## Current Phase

Phase 3-C — Market Data Persistence is build/unit complete.

Next: Phase 3-D — Instrument Master & Historical Sync.

Do not start Phase 3-D until Phase 3-C is accepted.

Phase 2.1 remains DEFERRED — physical device pending, and is not treated as FAIL.
