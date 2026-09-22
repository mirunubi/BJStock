# Signal Rules

Phase 9.1 hard triggers pinned to a strategy version.

## Table

`strategy_signal_rules`

## Supported metric (9.1)

`DAILY_CHANGE_PCT`

```text
(close_D / close_previous_trading_bar - 1) × 100
```

Uses adjusted close bars in Room. Previous means prior **trading** bar, not calendar day. Look-ahead (`D+1`) is forbidden.

## Operators / Actions

- Operators: `GTE`, `LTE`
- Actions: `BUY`, `SELL`
- Priority: lower number wins. Same priority with conflicting actions → activation FAIL.

## Evaluation order

1. Enabled signal rules (priority ASC, id ASC)
2. If a rule triggers → that BUY/SELL wins (`decision_source = SIGNAL_RULE`)
3. Else → existing factor weight / gate / threshold path (`FACTOR_STRATEGY`)

No rules → Phase 5 behavior unchanged.

## Immutability

DRAFT: upsert/delete allowed. ACTIVE/RETIRED: immutable. `copyDraftFrom` copies rules. Activation rejects conflicting same-priority actions.

## Execution timing

Rule signals follow Phase 6: signal on D → fill at next trading day open. Same-day close fill is forbidden.
