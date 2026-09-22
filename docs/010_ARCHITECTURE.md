# BJStock Architecture

This document records the target architecture. No runtime engines are implemented in Phase 0.

## Target Pipeline

```text
KIS Market Data
       ↓
Market Data Storage
       ↓
Factor Engine
       ↓
Scoring Engine
       ↓
Strategy Engine
       ↓
Decision
       ↓
Virtual Trading Engine
       ↓
Virtual Account
       ↓
Performance Analytics
```

## Components

### KIS Authentication

Manages App Key / App Secret and OAuth access tokens for KIS Open API.

Role:

- Encrypt secrets with Android Keystore AES/GCM
- Issue and cache OAuth tokens
- Keep authentication separate from market-data calls and from broker orders

### Market Data

Collects instrument metadata and market bars from KIS quotations APIs and official MST files.

Role:

- Provide a local historical and daily market dataset
- Remain read-only with respect to live brokerage orders

`market` is the exchange hierarchy (`KRX`). `board` is KOSPI, KOSDAQ, or OTHER. Instrument master download does not use the OAuth token. Historical daily sync is per selected instrument, never the whole market.

### Factor Engine

Computes factor values from stored Room market data only.

Role:

- Apply factor definitions to instruments
- Persist computed factor values for later scoring
- Never read future bars (`tradeDate > asOfDate`)
- Never call KIS during calculation

Phase 4 implements six market-data system factors. Missing values are not stored as zero. Results are factor values only; they are not BUY/SELL decisions.

### Scoring Engine

Converts factor values into comparable scores.

Role:

- Combine factor outputs into evaluation scores
- Keep scoring deterministic and inspectable

### Strategy Engine

Applies strategy versions and factor weights to scores.

Role:

- Version strategy definitions
- Produce a decision candidate from weighted scores
- Pin every enabled factor to an exact calculation version
- Enforce DRAFT/ACTIVE/RETIRED lifecycle and ACTIVE immutability
- Require exact-date factor values and exact scaled-integer weight totals
- Apply eligibility gates before emitting BUY/HOLD/SELL or NO_ACTION
- Keep Preview computation separate from immutable forward-evaluation snapshots

Phase 5 persists no orders, executions, or positions. BUY and SELL are evaluation results only.

### Virtual Trading

Owns paper cash, orders, executions, and positions for each strategy run.

Role:

- Treat each strategy run as an independent virtual account backed by `cash_ledger`
- Create paper orders from quant decisions under a deterministic policy
- Fill at next trading-day open only (never signal-day close)
- Keep fills atomic across order/execution/cash/position
- Never call KIS trading endpoints

Phase 6 does not implement WorkManager schedulers or historical backtest replay.

### Virtual Account

Maintains BJStock-owned cash, positions, and fills.

Role:

- Track virtual cash and holdings through `cash_ledger`
- Remain independent from real brokerage accounts

### Performance Analytics

Records portfolio snapshots and strategy outcomes.

Role:

- Support forward-test review
- Compare strategy versions over time

### AI Advisory

Optional side advisor. It is not on the execution path.

```text
Quant Decision ─┐
                ├─ Decision Record (final_decision = quant_decision)
AI Advice ──────┘  (advisory only)
```

Rules:

- Quant decision and AI advice are stored separately
- AI does not buy or sell
- The app remains usable with AI turned off
- Phase 8 supports OFF and CHATGPT_MANUAL; OPENAI_API_FUTURE is a non-network placeholder
- Future OpenAI access must use a backend gateway; no API secrets in the APK

## Runtime Boundary

BJStock is a local-only Android application.

- Android runtime data: Room / SQLite
- Docker PostgreSQL: development / schema laboratory only
- The APK does not connect to Docker PostgreSQL
- KIS App Key, App Secret, and access tokens are not stored in Room
- KIS secrets use Android Keystore encryption in app-private storage excluded from backup
- Phase 3-B market data is not written to Room
- Phase 3-C persists adjusted daily bars to Room for existing instruments only
