# Forward Test Dashboard

Phase 7 UI over read-only performance analytics.

## Entry

Main dashboard → **Forward Test**.

Routes:

```text
forward_test
compare_runs
```

## Dashboard Sections

0. **Orchestration** — Auto Forward Test OFF/ON, Run Now, Retry Failed Cycle, Last Complete, Latest Market Data, Status (UP TO DATE / CATCHING UP / WAITING FOR MARKET DATA / FAILED / BLOCKED)
0b. **Universe** — editable while DRAFT; read-only after READY
0c. **Cycle History** — recent market-date cycle statuses (safe error messages only)
1. **Summary** — strategy/version, run status, period, initial/current asset, cumulative return, MDD, CAGR (or N/A), closed trades, win rate, open positions, signal/execution counts
2. **Equity Curve** — Compose `Canvas` line of `total_asset` vs `snapshot_date` (not cumulative return)
3. **Monthly Returns** — table of year-month and signed percent
4. **Trade Statistics** — wins/losses/breakeven, win rate, average/best/worst trade return, average holding calendar days, open trades
5. **Open Positions** — symbol, qty, average price; optional latest stored close / market value / price-basis unrealized P/L (excludes commissions; labeled as such)
6. **Recent Executions** — side, symbol, simulated execution date, qty, price, commission, tax
7. **Trading Policy** — snapshot version, allocation, commission/tax **Simulation Assumption**, execution policy

## Compare Runs

Select up to 3 runs. Always show start/end dates and trading days with metrics so unequal periods are not implied to be comparable under identical conditions.

Shows each run’s policy version and assumption rates. Read-only — no strategy/policy/history edits.

## Color

Profit/loss may use theme colors, but always include `+` / `-` text labels.

## Out of Scope

- Stock ranking screens
- Benchmark (KOSPI/KOSDAQ) comparison
- AI commentary inside analytics
- Historical backtest replay
- New chart libraries (Canvas only)

WorkManager / automatic daily pipelines are Phase 9 (Auto default OFF). See [140_FORWARD_TEST_ORCHESTRATION.md](140_FORWARD_TEST_ORCHESTRATION.md) and [141_FORWARD_TEST_OPERATIONS.md](141_FORWARD_TEST_OPERATIONS.md).
