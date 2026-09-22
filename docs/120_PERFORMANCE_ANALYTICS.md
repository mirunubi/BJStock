# Performance Analytics

Phase 7 computes forward-test performance from existing Room history.

## Principles

- **Read-only** — never mutates `orders`, `executions`, `positions`, `cash_ledger`, `portfolio_daily_snapshots`, or `stock_evaluations`.
- **Local only** — no KIS / OpenAI / other network calls.
- **No schema change** — Room stays at version 5; no PostgreSQL migration.
- **No backtest replay** — analyzes stored forward-test history only.
- **No Sharpe / annualized volatility / benchmark** in this phase.

## Asset Source of Truth

```text
portfolio_daily_snapshots.total_asset
```

Cash / market value come from the same snapshot row. Analytics does not recompute positions × prices to overwrite snapshots.

## Metric Dictionary

### Cumulative Profit

```text
latestTotalAsset - initialCash
```

### Cumulative Return

```text
latestTotalAsset / initialCash - 1
```

Example: 100M → 112M ⇒ `+0.12` (+12%).

### Daily Return

First snapshot:

```text
dailyProfit = totalAsset - initialCash
dailyReturn = totalAsset / initialCash - 1
```

Later snapshots:

```text
dailyProfit = todayTotal - previousTotal
dailyReturn = todayTotal / previousTotal - 1
```

Missing weekends/holidays do **not** invent 0% rows. Only existing snapshot dates are used (`date ASC`).

### Monthly Return

Month-end asset = last existing trading snapshot in that calendar month.

First month:

```text
monthEnd / initialCash - 1
```

Later months:

```text
currentMonthEnd / previousMonthEnd - 1
```

Months with zero snapshots produce no row (no synthetic 0%).

### Maximum Drawdown (MDD)

On each date:

```text
peak = max(total_asset up to and including that date)
drawdown = total_asset / peak - 1
MDD = minimum(drawdown)   # <= 0
```

Look-ahead is forbidden: peaks use only history through that date.

### Win Rate

Closed trades only. Breakeven (`netProfit = 0`) is excluded from the denominator:

```text
winRate = winningTrades / (winningTrades + losingTrades)
```

If denominator is 0 ⇒ `N/A` (not 0%).

### Average Trade Return

Arithmetic mean of closed-trade `returnRate` values (not notional-weighted).

### Holding Days

```text
holdingDays = sellDate - buyDate   # calendar days
```

Uses `executed_at` as simulated market date (UTC midnight). Never `created_at`.

### CAGR

```text
elapsed calendar days < 365 → N/A
else (latestAsset / initialCash)^(365 / elapsedDays) - 1
```

## Closed Trade

Phase 6 policy (no averaging, full sell, no short) ⇒ one round trip:

```text
BUY execution → next SELL execution
```

per `(strategy_run_id, instrument_id)`, ordered by market execution date.

```text
buyCost  = buyGross + buyCommission
sellNet  = sellGross - sellCommission - tax
netProfit = sellNet - buyCost
returnRate = netProfit / buyCost
```

Malformed sequences (`SELL` without `BUY`, consecutive `BUY`) raise `TradeHistoryIntegrityError`.

## Empty Run

Zero snapshots ⇒ analytics status `EMPTY` (“No performance data yet”). Do not show fake 0% return / 0 MDD as a completed performance.

## Architecture

```text
PerformanceAnalyticsRepository  (read queries)
PerformanceAnalyticsService     (deterministic metrics)
PerformanceMath                 (BigDecimal ratios / formatting)
```

Money stays `Long`. Ratios use `BigDecimal`. Double/Float are not the source of truth (CAGR fractional power is the only IEEE pow helper, then stored as `BigDecimal`).
