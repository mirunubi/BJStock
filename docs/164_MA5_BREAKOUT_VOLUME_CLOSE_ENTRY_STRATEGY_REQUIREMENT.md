# 164 MA5 Breakout / Volume Surge / Close-Entry Strategy Requirement

- Document Status: DRAFT — Human Strategy Rules Incomplete
- ImplementationAuthority: NONE
- Strategy Source: Colleague request
- Human-approved point: B-option close-entry interpretation (HUMAN CONFIRMED)
- Working name: `MA5_BREAKOUT_VOLUME_2X_CLOSE_ENTRY_V1`
- Architecture Baseline: edcc29c
- Related: [docs/160](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md) (Intraday architecture, HUMAN APPROVED), [docs/161](161_INTRADAY_PROVIDER_PLATFORM_FEASIBILITY.md) (provider / platform feasibility, HUMAN APPROVED), [docs/163](163_SECURITY_AUDIT_FAILURE_CONTAINMENT_ARCHITECTURE.md) (security / audit architecture, DRAFT)
- Authoring Date: 2026-10-02

This document captures a colleague-requested, rule-based **paper-trading** strategy as a requirement draft. It is **not** a READY StrategyVersion, creates no StrategyVersion, and authorizes no Kotlin, Room, schema, migration, scheduler, provider, device, or credential work. Missing rules are recorded as OPEN; none are invented.

PAPER TRADING ONLY. No real brokerage order capability. KIS `/trading/` remains forbidden. Real trading is NOT AUTHORIZED.

## 0. Status Summary

| Item | Status |
| --- | --- |
| Strategy requirement | DRAFT — Human strategy rules incomplete |
| B-option entry interpretation | HUMAN CONFIRMED |
| StrategyVersion | NOT CREATED |
| Implementation | NONE |
| Real trading | NOT AUTHORIZED |
| READY blockers | Prior-below-MA5 definition; volume averaging basis; pre-close MA5 basis; entry decision time; D+1 target-not-hit exit rule; universe / risk / sizing rules; transaction costs / slippage; `POST_CLOSE_CONFIRMED = FALSE` handling; price-limit / VI / halt handling; execution-policy semantics (close-entry fill evidence, strict-after SELL execution, resting conditional order semantics); engine / Run model (§18) |
| Review delta | S164-F01 (strict-after SELL wording, §10): CLOSED — pending final delta review. READY-blocker summary expanded without changing strategy meaning |

## 1. Evidence Labels

| Label | Meaning |
| --- | --- |
| COLLEAGUE SOURCE INTENT | The colleague's stated intent, recorded as given |
| HUMAN CONFIRMED | Explicitly confirmed by the Human |
| APPROVED ARCHITECTURE | Already HUMAN APPROVED in docs/160 / docs/161 |
| CURRENT REPOSITORY FACT | Verified in the repository or approved docs at the stated baseline |
| PROPOSED | A candidate interpretation; not approved |
| OPEN | Requires colleague clarification and / or a Human decision before READY |

## 2. Purpose

Capture the colleague-requested strategy faithfully, freeze the one Human-confirmed point (B-option close entry), and list every rule that must be decided before the strategy can become a READY StrategyVersion — without implementing anything and without forcing it into an engine before architecture review (§14).

## 3. User-Provided Strategy Intent (COLLEAGUE SOURCE INTENT)

Recorded as given; no condition is added.

| # | Intent |
| --- | --- |
| I-1 | Find a stock that has been closing below the 5-day moving average for approximately one week / a sustained recent period ("하락 마감"). |
| I-2 | On the signal day, the stock breaks above the 5-day moving average. |
| I-3 | Buy near / at that day's close. |
| I-4 | Require volume of at least 200% of the recent one-week average. |
| I-5 | On the following trading day, sell if price rises at least 5%. |

Not stated by the source (therefore OPEN, §11–§12): what happens if the +5% target is not reached on the following trading day; stop loss; maximum holding period; universe; sizing; costs; exclusions.

## 4. B-Option Entry — HUMAN CONFIRMED

**Frozen:** the final confirmed closing price of the signal day D is **not known** before the order decision. The strategy is therefore:

```text
PRE-CLOSE DECISION  →  CLOSING-AUCTION / CLOSE-ENTRY ATTEMPT
```

and **not**:

```text
final close confirmed  →  retroactive same-close buy      (FORBIDDEN)
```

Conceptual flow (decision time and data sources OPEN, §8, §13):

| Step | When | Action |
| --- | --- | --- |
| 1 | At the approved pre-close decision time `decision_timestamp` | Evaluate whether the current provider-verifiable price satisfies the estimated / current 5-day MA breakout condition (basis OPEN, §7) |
| 2 | Same instant | Evaluate whether cumulative day volume at `decision_timestamp` satisfies the configured volume threshold (basis OPEN, §6) |
| 3 | Same instant | Evaluate the prior sustained-below-MA5 candidate condition from confirmed prior daily data (definition OPEN, §5) |
| 4 | If all entry conditions hold | Record `PRE_CLOSE_SIGNAL` and create a close-entry **paper** order intent |
| 5 | Closing auction / close-entry process | Determine the actual paper fill **only** from provider-verifiable closing-auction / closing-price evidence under a future execution policy (§9); otherwise NO FILL |
| 6 | After the official close is available | Calculate the final MA5 relationship using the official D close |
| 7 | After step 6 | Record `POST_CLOSE_CONFIRMED = TRUE` or `FALSE` |

- `PRE_CLOSE_SIGNAL` is the decision record; it uses only information available by `decision_timestamp`.
- `POST_CLOSE_CONFIRMED` is an after-the-fact classification; it never changes the decision, the order, or the fill.
- **A `FALSE` post-close confirmation does not erase the trade.** If a fill occurred, the position and its outcome remain a real strategy outcome and are reported as such. What the strategy does next for a `FALSE` case is OPEN (§12).

## 5. Prior Sustained-Below-MA5 Condition — OPEN

Exact meaning is **not** decided. Colleague clarification required.

| Item | Content | Status |
| --- | --- | --- |
| Source phrase | "closing below the 5-day moving average for approximately one week / a sustained recent period" ("하락 마감") | COLLEAGUE SOURCE INTENT |
| Candidate interpretation A | At least 5 consecutive trading days immediately before D (D-5 … D-1) where `Close[t] < MA5[t]` | PROPOSED — NOT Human-confirmed |
| Open question Q-5.1 | Does "하락 마감" mean only "close below MA5", or does it also require the close price itself to decline day by day (`Close[t] < Close[t-1]`)? | OPEN — colleague clarification required |
| Open question Q-5.2 | Exact length ("approximately one week" = 5 trading days? at least 5? a ratio of days within a window?) | OPEN |
| Open question Q-5.3 | Must the run be strictly consecutive, or are interruptions tolerated? | OPEN |
| Open question Q-5.4 | Definition of `MA5[t]` for prior days: simple average of the five confirmed closes `t-4 … t`? | OPEN (simple moving average of confirmed closes is the natural reading, not yet confirmed) |

Data consequence (informational): under candidate A, evaluating `MA5[t]` for D-5 … D-1 needs confirmed closes from D-9 through D-1. All of these are prior confirmed values and are known before D (no look-ahead).

## 6. Volume Condition — Requirement Captured, Basis OPEN

| Item | Content | Status |
| --- | --- | --- |
| Requirement | Signal-day volume ≥ 200% of the recent one-week average volume | COLLEAGUE SOURCE INTENT — CAPTURED |
| Q-6.1 | Average over the prior 5 trading days, or over the calendar week's trading days? | OPEN |
| Q-6.2 | Include or exclude the signal day D from the average? | OPEN |
| Q-6.3 | Signal-day volume at decision time is **cumulative intraday volume up to `decision_timestamp`**, not the full-day volume (the full-day volume, including the closing auction, is not known before the decision) | Follows from the B-option and anti-look-ahead rules (§4, §15) |
| Recommended clean candidate | `AVG_VOL_5 = mean(Volume[D-5 … D-1])` (D excluded); entry requires `cumulative_volume[D, decision_timestamp] ≥ AVG_VOL_5 × 2.0` | **PROPOSED, NOT YET HUMAN-APPROVED** |
| Q-6.4 | Whether the multiplier 2.0 is a fixed rule or a configurable StrategyVersion parameter | OPEN |

Note (informational, not a decision): comparing partial-day cumulative volume with full-day historical averages is stricter than a full-day comparison would be; whether that is the colleague's intent is part of Q-6.

## 7. MA5 Calculation at Pre-Close — OPEN

Because the D close is unknown at decision time, the MA5 value that includes D cannot be the official one at decision time.

| Item | Content | Status |
| --- | --- | --- |
| `PRE_CLOSE_MA5_BASIS` | How MA5 is evaluated at `decision_timestamp` | **OPEN** |
| Possible future design (not frozen) | Estimated MA5 = (current provider-verifiable price at `decision_timestamp` + the four prior confirmed daily closes D-1 … D-4) / 5 | PROPOSED candidate only |
| Breakout test under that candidate (informational) | `P_decision > MA5_est`, which is algebraically equivalent to `P_decision > mean(Close[D-4 … D-1])` | Informational only |
| Alternative bases to consider | Compare against the prior day's official `MA5[D-1]`; use a different reference price (for example last trade vs best quote) | OPEN |
| Post-close confirmation basis | `Close[D] > MA5[D]` with the official close, computed after the official close (§4 step 6) | Defined for `POST_CLOSE_CONFIRMED`; exact comparison operator (`>` vs `≥`) OPEN |

## 8. Entry Timing — OPEN

The exact decision time is **not** approved. Possibilities, conceptually:

- before the closing auction;
- at the start of the closing auction;
- another provider-supported pre-close decision slot.

No time is hardcoded (no 15:20, 15:30, or any other value) without evidence and Human approval. The choice must be compatible with KRX market mechanics and provider evidence:

- APPROVED ARCHITECTURE / CURRENT REPOSITORY FACT (docs/161 §18): KRX regular session 09:00–15:30 with a closing call auction 15:20–15:30 (single print at 15:30) per the KIS guide; how auction prints appear in provider data is **UNVERIFIED** (docs/161 M-08, M-25).
- docs/161 §18 also records a separate KRX after-hours closing-price session (15:40–16:00). That mechanism is **not** the Human-confirmed B option (which decides before the close) and is listed only so that a later Human decision can consider or reject it explicitly.

## 9. Paper Fill Semantics

- **No fabricated fill.** The future execution policy must specify which provider-verifiable evidence (for example the official closing-auction print with its time and venue) constitutes a close-entry fill.
- If a close-entry order is emitted but no valid fill evidence exists → **NO FILL + audit reason**, never a synthetic closing-price fill.
- Consistency with APPROVED ARCHITECTURE (docs/160 §21): an execution price must represent a market observation strictly after the paper order existed. A closing-auction print can satisfy this only if the order intent exists before the auction concludes and the print is provider-verifiable; otherwise NO FILL.
- Trading costs (commission, sell tax where applicable, slippage) apply per docs/160 §21 / §36 #8; values are not set here. A zero-cost run is allowed only as an explicitly labelled scenario.

## 10. Next-Day +5% Exit (Known Rule)

| Item | Content |
| --- | --- |
| Rule | On the next trading day after entry (D+1): `target = verified_entry_fill_price × 1.05`. If a provider-verifiable observation reaches or exceeds `target`, a **SELL signal** occurs |
| Execution | **Target detection and SELL execution are separate moments** (strict-after, docs/160 §21). The observation that triggers the SELL signal is **not** the fill. After the SELL signal, a SELL order / intention is created; the paper execution then uses the **first eligible provider-verifiable price strictly after the SELL order exists**. **No fabricated exact +5.000% fill.** The eligible execution price may be above or below `target` |
| Resting conditional order | A model in which a resting conditional (for example limit-at-target) SELL order exists **before** the triggering price, so that the triggering print itself could be a valid fill, is **OPEN**. It applies only if a future execution policy explicitly models such an order; it is not assumed here |
| Monitoring truthfulness | If monitoring is slot-based, the check is only as fine as the slot cadence (docs/160 §22 "15분 기준" wording precedent); a price can touch the target between checks without a SELL signal being observed |
| Q-10.1 | Is `verified_entry_fill_price` the raw fill price or the cost / slippage-adjusted price? | OPEN |
| Q-10.2 | Which price observation counts as "reaches" (trade print, bar high, slot price)? | OPEN |
| Architecture note | A position-dependent target is an exit-policy concept, not a market factor; docs/160 §22 assigns such exits to a dedicated exit-policy stage that is architecturally supported but default OFF. How this strategy uses that stage is decided in architecture review (§14) |

Conceptual SELL sequence (S164-F01):

```text
provider-verifiable observation reaches / exceeds target (+5%)
        ↓
SELL SIGNAL                                  (decision record; not a fill)
        ↓
SELL order / intention created               (order_created_at)
        ↓
paper execution at the first eligible provider-verifiable price
STRICTLY AFTER the SELL order exists         (execution_price_time > order_created_at)
        or NO FILL + audit reason if no eligible evidence exists
```

The triggering observation is never treated as the execution price of an order created after it. A triggering price could count as a fill only under a future execution policy that explicitly models a valid resting conditional order existing before that price (OPEN, above).

If there is no fill on D, there is no position and no D+1 target.

## 11. Major Open Exit Rule — Target Not Hit on D+1

The source does **not** specify what happens if D+1 never reaches +5%.

**Status: OPEN — REQUIRED BEFORE READY.**

Possible future choices (listed, **none selected**):

- exit at the D+1 close;
- continue holding until the target is reached;
- exit on an MA5-based rule;
- a stop loss;
- a maximum N-day holding period;
- a combined rule.

No stop loss and no holding period are assumed by this document.

## 12. Other Open Strategy Questions

All OPEN unless already covered by canonical architecture (noted where applicable).

| # | Question | Status |
| --- | --- | --- |
| Q-12.1 | Universe: KOSPI, KOSDAQ, or both? (`Board` enum has `KOSPI`, `KOSDAQ`, `OTHER`; CURRENT REPOSITORY FACT) | OPEN |
| Q-12.2 | Exclusions: suspended issues, SPACs, ETF / ETN (`InstrumentType.ETP` exists), preferred shares (`InstrumentType.PREFERRED_STOCK` exists), others? | OPEN |
| Q-12.3 | Minimum price? | OPEN |
| Q-12.4 | Minimum liquidity (value traded / volume)? | OPEN |
| Q-12.5 | Behaviour at upper / lower daily price limits? | OPEN |
| Q-12.6 | Gap-up behaviour on D (for example D opens already above MA5)? | OPEN |
| Q-12.7 | VI (volatility interruption) behaviour, including a VI during the decision window or the closing auction? | OPEN |
| Q-12.8 | Trading-halt behaviour on D or D+1? | OPEN |
| Q-12.9 | Multiple simultaneous candidates: ranking / tie-break? | OPEN |
| Q-12.10 | Maximum number of entries per day? | OPEN |
| Q-12.11 | Position sizing? | OPEN (current DAILY sizing is a Run paper-policy allocation; reuse not decided) |
| Q-12.12 | Capital allocation across positions? | OPEN |
| Q-12.13 | Transaction-cost and slippage values? | OPEN (cost inclusion required by docs/160 §36 #8; values not set) |
| Q-12.14 | If `POST_CLOSE_CONFIRMED = FALSE`: hold normally or apply a special exit? | OPEN |
| Q-12.15 | Same-stock re-entry rules? | OPEN (docs/160 §20.1 pending-exposure invariant and "additional buy DISALLOW" apply to Intraday Runs; applicability to this hybrid strategy decided in architecture review) |
| Q-12.16 | Holiday / shortened-session handling for D and D+1 (official calendar required, docs/160 §31) | OPEN |

## 13. Data Requirements (Conceptual)

No provider implementation decision is made here.

| Data | Use | Timing constraint |
| --- | --- | --- |
| Prior confirmed daily OHLCV (at least D-9 … D-1 under candidate A) | Prior-below-MA5 condition; MA5 of prior days; `AVG_VOL_5` | Confirmed before D |
| Current-day cumulative volume at `decision_timestamp` | Volume trigger | `available_at ≤ decision_timestamp` |
| Pre-close provider-verifiable price at `decision_timestamp` | Breakout trigger, estimated MA5 | `available_at ≤ decision_timestamp` |
| Closing-auction / official close evidence for D | Paper fill (if policy-eligible); `POST_CLOSE_CONFIRMED` | After the close; never used for the D decision |
| Next-day (D+1) intraday / eligible observations | +5% target monitoring and SELL execution | Strictly after the relevant order / decision instants |

CURRENT REPOSITORY FACT (docs/161 §2, at `5d709b0`): the existing KIS current-price mapping includes current price and cumulative volume fields, and daily bars include OHLCV; no intraday time field is mapped and no closing-auction evidence is captured. Whether and how these are used is an architecture / provider decision, not made here.

## 14. Engine Intersection

This strategy is likely a **hybrid** of DAILY CONTEXT and INTRADAY / PRE-CLOSE EXECUTION:

```text
Daily history (confirmed closes / volumes)
  → sustained-below-MA5 candidate state                    (DAILY CONTEXT)

Pre-close intraday observation at decision_timestamp
  → breakout + volume trigger → PRE_CLOSE_SIGNAL            (INTRADAY / PRE-CLOSE)

Closing-entry execution policy
  → provider-verifiable closing evidence → paper fill / NO FILL

Official close
  → POST_CLOSE_CONFIRMED = TRUE / FALSE                     (classification only)

Next-day intraday observation
  → +5% target monitoring → SELL signal → SELL order → strict-after paper fill / NO FILL
```

Why neither existing nor planned engine fits as-is (CURRENT REPOSITORY FACT / APPROVED ARCHITECTURE):

- DAILY (docs/160 §2): evaluation happens after the session (07:00 KST next-day Auto slot), the only execution policy is `NEXT_TRADING_DAY_OPEN`, and the only signal metric is `DAILY_CHANGE_PCT`. It cannot express a pre-close decision with a same-day close entry.
- INTRADAY_15M (docs/160 §3, §11, §21): built around a repeated 15-minute slot grid, and a Run is either DAILY or INTRADAY_15M for its whole life. A single pre-close decision plus closing-auction execution plus a next-day exit is not yet modelled.

**Do not force this strategy into the DAILY engine or the new Intraday engine until architecture review.** The engine / Run model choice is an OPEN architecture decision.

## 15. Anti-Look-Ahead Invariant (HARD RULE)

- No final D close value (official close, final daily volume, final daily high / low, final MA5 including D) may be used to make a decision that claims to have occurred before the D close.
- No retrospective same-close synthetic fill.
- Decision evidence uses only information with `available_at ≤ decision_timestamp`.
- `POST_CLOSE_CONFIRMED` is computed only after the official close and never feeds back into the D decision, order, or fill.
- This aligns with the existing BJStock point-in-time rules (docs/160 §12: only inputs available by the decision cutoff are eligible; no retroactive trades, docs/160 §29–§30; strict-after execution, docs/160 §21).

## 16. Audit / Security Requirements

The strategy must eventually audit, through the canonical BJStock domain / audit architecture (`trade_audit_logs`, `operational_events`, `orders`, `executions`, and the docs/163 integrity layer, which commits to those records):

| Record | Content |
| --- | --- |
| Candidate qualification | Prior-below-MA5 evaluation inputs and result |
| Decision timestamp | `decision_timestamp` |
| Available input timestamps | `available_at` / `received_at` of every input used |
| MA5 basis | Basis identifier and inputs used (§7) |
| Volume basis | Average definition, window, cumulative volume at decision (§6) |
| `PRE_CLOSE_SIGNAL` | Decision record with reason codes |
| Order intent | Paper close-entry order and its creation time |
| Fill / no-fill | Fill evidence (source, time, venue) or NO FILL with reason |
| Official closing confirmation | Official close value and its source / time |
| `POST_CLOSE_CONFIRMED` | TRUE / FALSE with the computation inputs |
| Next-day target | `target` and its derivation from the verified fill |
| Target hit / not hit | Observation that triggered or the absence of one |
| Exit signal | SELL signal and the observation / decision source that triggered it |
| SELL order | SELL order / intention and its creation time |
| Execution evidence | SELL fill evidence strictly after the SELL order existed (source, time), or NO FILL with reason |
| Reason codes | From the central reason-code registry (docs/163 §45) |

**No separate strategy-specific audit ledger** is created (docs/160 §0.2; docs/163 §8.1).

## 17. Future Backtest Warning

- A backtest must **not** simulate the B option as "confirmed D close → same D close buy"; that introduces look-ahead.
- Historical B-option research requires a data source / approximation that represents the actual pre-close decision point (price and cumulative volume as of `decision_timestamp`) and the closing execution semantics.
- If such data is unavailable, any backtest must be explicitly labelled as an approximation (for example "OPTIMISTIC CLOSE-ENTRY APPROXIMATION"), stored apart from Forward Test evidence, and never presented as live-equivalent performance (same principle as docs/160 §21 for offline analytical references).

## 18. Status

**Strategy Requirement Status: DRAFT.**

Not READY because at least these remain OPEN:

1. Exact prior-below-MA5 definition (§5).
2. Volume averaging semantics (§6).
3. Pre-close MA5 basis (§7).
4. Exact entry decision time (§8).
5. D+1 target-not-hit exit rule (§11).
6. Universe / risk / position-sizing rules (§12).
7. Transaction-cost and slippage values (§9, Q-12.13).
8. `POST_CLOSE_CONFIRMED = FALSE` handling (§4, Q-12.14).
9. Price-limit, VI, and trading-halt handling (Q-12.5, Q-12.7, Q-12.8).
10. Execution-policy semantics: close-entry fill evidence (§9), strict-after SELL execution, and resting conditional order semantics (§10).

Also required before READY: the engine / Run model decision (§14).

All of these were already OPEN; listing them here decides none of them.

No StrategyVersion creation. No implementation. No real trading.

---

PAPER TRADING ONLY. No real brokerage order capability. KIS `/trading/` remains forbidden. ImplementationAuthority: NONE.
