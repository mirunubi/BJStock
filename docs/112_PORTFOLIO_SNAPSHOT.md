# Portfolio Snapshot

Trading-day portfolio valuation for a strategy run.

## Inputs

- Current cash from `cash_ledger`
- Open positions (`quantity > 0`)
- Exact-date `market_daily_bars.close_price` for each open instrument

No network quotes. No previous-day close substitute.

## Formulas

```text
market_value = Σ(quantity × close)
total_asset = cash + market_value
cumulative_profit = total_asset - initial_cash
cumulative_return = total_asset / initial_cash - 1
```

Daily profit/return use the previous snapshot's `total_asset` when present; otherwise `initial_cash`.

Drawdown:

```text
peak = max(prior peak through previous snapshot dates, today's total_asset)
drawdown = total_asset / peak - 1   # ≤ 0
```

Returns/drawdown are stored with `RATIO_FACTOR = 100_000_000`.

## Immutability

`(strategy_run_id, snapshot_date)` is unique. Existing snapshots return `ALREADY_EXISTS` and are not overwritten.

## Missing Price

If any open position lacks a close for the snapshot date, snapshot creation fails (`SNAPSHOT_FAIL`).
