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

## Trading Policy

Each strategy run owns one immutable `paper_trading_policies` snapshot (Phase 6.1). See [113_PAPER_TRADING_POLICY.md](113_PAPER_TRADING_POLICY.md).

Live fills load that snapshot — never the live code default.

`PaperTradingPolicy.DEFAULT` is only the template when creating a new run.

v1 baseline (SIMULATION ASSUMPTION):

- BUY allocation: **10% of current cash**
- Additional buy into an open position: **forbidden**
- SELL: **full position only**
- Short selling: **forbidden**
- HOLD / NO_ACTION: no order
- commission `0.00015`, sell tax `0.0020`, slippage `0`

Missing snapshot → `MISSING_TRADING_POLICY` (no silent global fallback).

## Execution Timing

Signal date `D` never fills at `D` close.

```text
Execution Date = first market_daily_bars.trade_date > D
Execution Price = that bar's open (+ optional slippage policy)
```

If no later bar exists, the order stays `PENDING_EXECUTION`.

## Costs

Costs come from the run's policy snapshot (`commission_rate`, `sell_tax_rate`, `slippage_bps`).

**SIMULATION ASSUMPTION** — not claimed to be current legal/brokerage rates.

## executed_at

```text
executed_at = simulated market execution date (UTC midnight Instant)
created_at  = wall-clock DB insert time
```

Do not use `created_at` as the trading day.

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
