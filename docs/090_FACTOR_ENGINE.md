# Factor Engine

Phase 4 computes market-data factors from Room daily bars. It does not create strategy weights, BUY/HOLD/SELL, portfolio state, or AI output.

```text
KIS
 ↓
Room market_daily_bars
 ↓
FactorCalculator (asOfDate)
 ↓
rawValue
 ↓
FactorNormalizer
 ↓
score 0..100
 ↓
factor_values  (SUCCESS only)
```

## Look-ahead rule

For `evaluationDate = D` the engine may read only bars with `tradeDate <= D`.

`findBarsUpToDate` enforces this in SQL. Every calculator also drops any bar after `asOfDate` before computing.

If `asOfDate` has no daily bar, the result is `NO_DATA`. Friday values are not copied onto Saturday.

## Network

Factor calculation never calls KIS. Missing history is `INSUFFICIENT_HISTORY` or `NO_DATA`, not a download.

## Determinism

The same instrument, asOfDate, calculation version, and stored bars always produce the same raw value and score. Calculators do not use `now()` or randomness.

Arithmetic uses `BigDecimal` (`MathContext(16, HALF_UP)`). Stored `raw_value` is a locale-independent decimal string with scale 8.

## Calculators

`FactorCalculator.calculate(instrumentId, asOfDate, bars)` returns a raw outcome. History lengths are trading-day rows in Room, not calendar days.

| Status | Meaning | Stored |
| --- | --- | --- |
| SUCCESS | raw + score present | yes |
| INSUFFICIENT_HISTORY | asOf bar exists, window too short | no |
| NO_DATA | no bar on asOfDate | no |
| INVALID_DATA | division by zero / non-positive close | no |

Missing is not a zero score.

## Normalizer

Raw calculation and 0..100 mapping are separate. Phase 4 uses linear clamp mapping, plus piecewise linear for volume ratio.

These bounds are a **Phase 4 baseline** and may change after forward test. They live in `SystemFactorCatalog`.

Scores are stored with the existing Room scale: `normalized_score` Long = score × 10_000.

## Calculation version

Central value: `v1` (`FactorCalculationVersions.V1`).

Unique key: `(instrument_id, factor_id, evaluation_date, calculation_version)`.

Re-running `v1` UPSERTs raw/score/source and keeps the row id. A new version inserts another row.

## Persistence

Source for system market factors: `BJSTOCK_MARKET_ENGINE`.

One SUCCESS row is atomic. A batch of six factors stores only the SUCCESS subset. MA20 can persist while MA60 is still short.

## Ownership

System factors are the six codes in `FactorCodes.SYSTEM`. No extra schema column is required yet. User-defined factors can be added later as additional `factor_definitions` rows.
