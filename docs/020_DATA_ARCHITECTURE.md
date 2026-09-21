# BJStock Data Architecture

Phase 0 records domain candidates only.

No business tables are created in this phase. SQL DDL for domain tables belongs to Phase 1.

## Runtime vs Laboratory

| Store | Technology | Role |
| --- | --- | --- |
| Android runtime DB | Room / SQLite | App data on device |
| Development DB | Docker PostgreSQL | Schema, PK/FK, constraint, index, and SQL experiments |

Docker PostgreSQL is not the Android runtime database.

## Domain Candidates

### Market

- `instruments`
- `market_daily_bars`

Purpose: listed instruments and daily market bars used by later factor calculation.

### Factor

- `factor_definitions`
- `factor_values`

Purpose: named factor logic and computed values per instrument and date.

### Strategy

- `strategies`
- `strategy_versions`
- `strategy_factor_weights`

Purpose: strategy identity, versioned definitions, and factor weights.

### Evaluation

- `stock_evaluations`
- `stock_evaluation_details`

Purpose: scored evaluation results and per-factor contribution details.

### Paper Trading

- `trading_accounts`
- `positions`
- `orders`
- `executions`

Purpose: virtual account state, holdings, paper orders, and fills.

### Performance

- `portfolio_daily_snapshots`

Purpose: daily virtual portfolio snapshots for forward-test analytics.

### AI

- `ai_advice_requests`
- `ai_advice_results`

Purpose: optional AI request/result history. These tables must not be required for trading.

## Phase 0 Schema

The development database may contain only:

```sql
CREATE SCHEMA IF NOT EXISTS bjstock;
```

Business tables are deferred to Phase 1.
