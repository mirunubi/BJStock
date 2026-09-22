# Virtual Account

Each `strategy_runs` row is one independent virtual account.

## Initial Cash

Creating a READY run (one transaction):

```text
strategy_runs insert
  → paper_trading_policies snapshot (from template)
  → cash_ledger INITIAL_DEPOSIT
  → status READY
```

INITIAL_DEPOSIT:

```text
cash_ledger.event_type = INITIAL_DEPOSIT
amount = +initial_cash
balance_after = initial_cash
```

Duplicate initial deposits are rejected. Duplicate policy snapshots for the same run are rejected.

## Cash Ledger Events

MVP events:

- `INITIAL_DEPOSIT`
- `BUY` (negative gross)
- `SELL` (positive gross)
- `COMMISSION` (negative)
- `TAX` (negative)

`ADJUSTMENT` exists in schema for future use.

## Current Cash

```text
current_cash = latest cash_ledger.balance_after for the run
```

`balance_after` must never be negative.

## BUY Cash Impact

```text
BUY amount = -(price × quantity)
COMMISSION amount = -commission
```

Quantity is the largest whole-share amount such that gross + commission stays within the allocation budget.

## SELL Cash Impact

```text
SELL amount = +(price × quantity)
COMMISSION amount = -commission
TAX amount = -tax
```

## Positions

- BUY into empty/zero quantity sets quantity and average price.
- SELL to flat keeps the row with `quantity = 0`, `average_price = 0`, and accumulates `realized_profit`.
- Realized P&L uses net sell proceeds minus cost basis.
