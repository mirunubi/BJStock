# Strategy Engine

Phase 5 turns persisted factor values into deterministic strategy judgments. It does not create orders, executions, or positions.

## Strategy Lifecycle

- `DRAFT`: thresholds, description, weights, factor calculation versions, enabled flags, and gates are editable.
- `ACTIVE`: eligible for new paper strategy runs and persisted evaluations. Core configuration is immutable.
- `RETIRED`: retained for historical queries, but cannot start a new strategy run.

Changing an ACTIVE strategy requires copying it to the next sequential version. The copy is DRAFT and includes thresholds, weights, pinned calculation versions, and gates.

## Version Immutability

`StrategyVersionService` guards all mutation APIs. PostgreSQL and SQLite triggers are intentionally not used. An ACTIVE or RETIRED version rejects threshold, weight, enabled-factor, calculation-version, and gate changes.

## Factor Version Pinning

Each `strategy_factor_weights` row stores `factor_calculation_version`. Factor lookup uses:

```text
factor_code + calculation_version
```

The system registry currently contains the six system factors at `v1`. Adding `v2` does not remove or silently replace `v1`.

## Weights

Room stores weights as scaled `Long` values using the existing `1,000,000` scale. Activation requires at least one enabled factor and an exact enabled-weight sum of `1,000,000`. Disabled factors are excluded from weight totals, missing-data checks, gates, and score calculation.

## Thresholds

Thresholds use the existing score scale of `10,000` and must satisfy:

```text
0 <= sell_threshold < buy_threshold <= 100
```

## Factor Gates

`min_score` and `max_score` are eligibility gates, not score clamps. A score below min or above max produces `NO_ACTION`. Equality passes. Gate settings must be in 0..100 and min must not exceed max.

## Missing Factor Policy

Every enabled factor must have a value for the exact instrument, evaluation date, factor ID, and pinned calculation version. Missing values are never replaced with zero, prior-day values are never substituted, and remaining weights are never renormalized.

## Evaluation Snapshot

Persisted evaluations copy raw value, factor score, weight, and weighted score into detail rows. Later factor-value recalculation does not modify the snapshot.

## Preview vs Forward Evaluation

`PreviewStrategyEvaluationUseCase` evaluates DRAFT or ACTIVE versions without writing evaluation tables.

`EvaluateStrategyRunUseCase` derives the strategy version from the run, accepts only READY/RUNNING runs with an ACTIVE version, and writes an immutable snapshot. A duplicate run/instrument/date returns `ALREADY_EVALUATED`.
