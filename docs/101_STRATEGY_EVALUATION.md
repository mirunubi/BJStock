# Strategy Evaluation

## Weighted Score Formula

For every enabled factor:

```text
weighted_score = factor_score × weight
quant_score = SUM(weighted_score)
```

The implementation uses `BigDecimal` and `HALF_UP`. Intermediate snapshot contributions use the existing ratio scale of `100,000,000`; final scores use the existing score scale of `10,000`. `Double` and `Float` are not used.

## BUY / HOLD / SELL

Given valid `sell_threshold < buy_threshold`:

```text
score >= buy_threshold  → BUY
score <= sell_threshold → SELL
otherwise               → HOLD
```

Threshold equality is inclusive.

## NO_ACTION

`NO_ACTION` means all required factor data exists but an eligibility gate failed. The computed quant score may be persisted, while the quant and final decisions are `NO_ACTION`.

`INSUFFICIENT_FACTORS` is different: evaluation cannot be completed and no forward-evaluation row is created.

## Evaluation Persistence

Forward evaluation is allowed only for READY or RUNNING strategy runs whose version is ACTIVE. The run supplies `strategy_version_id`; callers cannot supply a different version.

The evaluation header and all enabled-factor details are inserted in one Room transaction. Partial snapshots roll back.

## Snapshot Semantics

`stock_evaluation_details` permanently records:

- factor ID
- raw value
- normalized factor score
- strategy weight
- weighted contribution

The ACTIVE strategy version supplies its immutable thresholds, gates, and pinned factor calculation versions. Together, these records explain the historical decision without reading mutable current factor values.

## No Re-Evaluation Rule

`(strategy_run_id, instrument_id, evaluation_date)` is unique. A second attempt returns `ALREADY_EVALUATED` and does not overwrite the original snapshot. Recalculation belongs in Preview to avoid retrospective and look-ahead bias.

## AI Fields Policy

Phase 5 does not use AI:

```text
ai_score      = NULL
final_score   = quant_score
final_decision = quant_decision
```

A BUY or SELL decision remains a judgment only. It does not create an order, execution, or position.
