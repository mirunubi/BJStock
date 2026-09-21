# BJStock Development Plan

## Roadmap

### Phase 0 — Project Foundation

Git bootstrap, documentation, and Docker PostgreSQL laboratory.

### Phase 1 — DB Architecture

Design and validate domain schema in Docker PostgreSQL.

### Phase 2 — Android Foundation

Create the Android app shell. No live trading. Runtime DB will be Room / SQLite.

### Phase 3 — KIS Market Data

Ingest market data from KIS Open API. Market data only. No live orders.

### Phase 4 — Factor Engine

Define factors and compute factor values from stored market data.

### Phase 5 — Scoring / Strategy Engine

Score instruments and apply strategy weights/versions.

### Phase 6 — Virtual Account / Paper Trading

Implement BJStock-owned virtual account, paper orders, and executions.

### Phase 7 — Performance Analytics

Track forward-test results through portfolio snapshots and related metrics.

### Phase 8 — AI Advisory

Add optional AI advice. AI remains off-by-default capable and never executes trades.

### Phase 9 — Long-Term Forward Test

Run paper trading for one year or longer and review strategy quality.

### Future — Live Trading

Live broker orders are explicitly out of current scope.

## Current Phase

Phase 0 — Project Foundation

Do not start the next phase until Phase 0 is complete and accepted.
