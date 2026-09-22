# Paper Trading Engine

Phase 6 implements BJStock-owned virtual paper trading. It never calls KIS trading APIs.

## Flow

```text
Strategy Evaluation
  → Paper Trading Policy
  → Virtual Order (PENDING_EXECUTION or REJECTED)
  → Next trading-day Open fill
  → Cash ledger + Position projection
  → Portfolio daily snapshot
```

## Source of Truth

- `orders` + `executions` are the trade history.
- `positions` are a current projection.
- `cash_ledger` is the cash history; current cash is the latest `balance_after`.
- `portfolio_daily_snapshots` are trading-day performance projections.

## Trading Policy (MVP)

- BUY allocation: **10% of current cash** (`PaperTradingPolicy.buyAllocationPercent`).
- Additional buy into an open position: **forbidden**.
- SELL: **full position only**.
- Short selling: **forbidden**.
- HOLD / NO_ACTION: no order.

Policy rates live in code for Phase 6. Persisting them per run is deferred.

## Execution Timing

Signal date `D` never fills at `D` close.

```text
Execution Date = first market_daily_bars.trade_date > D
Execution Price = that bar's open (+ optional slippage policy)
```

If no later bar exists, the order stays `PENDING_EXECUTION`.

## Costs

`TradingCostPolicy` and `SlippagePolicy` are injectable.

**SIMULATION ASSUMPTION** defaults:

- commissionRate = 0.00015
- sellTaxRate = 0.0020
- slippage = 0

These are not claimed to be current legal/brokerage rates.

## Atomic Fill

One Room transaction updates:

- order status
- execution row
- cash ledger rows
- position projection

## Deterministic Multi-BUY

Same-day pending BUY fills are processed by `evaluation_id ASC`, then `id ASC`.

## Network

Paper trading reads only Room data. No WorkManager / AlarmManager / scheduler in Phase 6.
