# 164 MA5 Breakout / Volume Surge / Close-Entry Strategy Template Requirement

- Document Status: DRAFT — Human Strategy Rules Incomplete
- ImplementationAuthority: NONE
- StrategyVersion: NOT CREATED
- Strategy Source: Colleague request; colleague clarifications relayed by the Human on 2026-10-02
- Human-confirmed points: B-option close-entry interpretation; sustained-below-MA entry condition and its default duration; take-profit rule; stop-loss rule armed for the entire holding period; maximum holding period with final-day exit; configurable Strategy Template product requirement (documentation level only)
- Working template family: `MA5_BREAKOUT_VOLUME_CLOSE_ENTRY_TEMPLATE` (supersedes the earlier working name `MA5_BREAKOUT_VOLUME_2X_CLOSE_ENTRY_V1`, which encoded a fixed 2× volume multiplier that is now a Template parameter)
- Architecture Baseline: edcc29c; revision base c3da161
- Related: [docs/160](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md) (Intraday architecture, HUMAN APPROVED), [docs/161](161_INTRADAY_PROVIDER_PLATFORM_FEASIBILITY.md) (provider / platform feasibility, HUMAN APPROVED), [docs/163](163_SECURITY_AUDIT_FAILURE_CONTAINMENT_ARCHITECTURE.md) (security / audit architecture, APPROVED — Phase 12-S0 Architecture Closed)
- Authoring Date: 2026-10-02 (template revision 2026-10-02)

This document captures a colleague-requested, rule-based **paper-trading** strategy as a requirement draft and expands it into a **configurable Strategy Template** requirement. It is **not** a StrategyVersion, creates no StrategyVersion and no Run, and authorizes no Kotlin, Compose UI, Room, schema, migration, scheduler, provider, device, or credential work. Missing rules are recorded as OPEN; none are invented.

Section numbers §0–§18 are kept stable because docs/163 cross-references them (§10, §18). The Strategy Template requirement is appended as §19–§27.

PAPER TRADING ONLY. No real brokerage order capability. KIS `/trading/` remains forbidden. Real trading is NOT AUTHORIZED.

## 0. Status Summary

| Item | Status |
| --- | --- |
| Strategy requirement | DRAFT — Human strategy rules incomplete |
| B-option entry interpretation | HUMAN CONFIRMED |
| Sustained-below-MA entry condition (`Close[t] < MA[t]` for N consecutive trading days; no daily-decline requirement) | HUMAN CONFIRMED (§5) |
| Default below-MA duration | HUMAN CONFIRMED — 5 trading days, configurable (§5) |
| Volume threshold default | HUMAN CONFIRMED — 200%, configurable; averaging basis OPEN (§6) |
| Take-profit | HUMAN CONFIRMED — +5% from actual entry fill, active throughout the holding period, configurable (§10) |
| Stop-loss | HUMAN CONFIRMED — −5% from actual entry fill (`stop_loss_percent = 5`, positive magnitude), armed for the entire holding period and evaluated at every eligible monitoring observation, configurable (§10) |
| Maximum holding period / final-day exit | HUMAN CONFIRMED — 10 trading days, configurable; position must be exited on the final permitted trading day (§10) |
| Former OPEN item "D+1 target not hit" | CLOSED — superseded by the confirmed exit model (§11) |
| Strategy Template (editable Human configuration) | PRODUCT REQUIREMENT DOCUMENTED (§19–§27); no persistence, UI, or runtime defined |
| StrategyVersion | NOT CREATED |
| Implementation | NONE |
| Real trading | NOT AUTHORIZED |
| READY blockers | Pre-close MA basis; volume averaging semantics; entry decision time; holding-period details (trading-day counting, time-exit timing, monitoring eligibility, exit precedence, SELL no-fill handling); universe / exclusions; price-limit / VI / halt handling; simultaneous signals, max positions, sizing, capital allocation; transaction costs / slippage; `POST_CLOSE_CONFIRMED = FALSE` handling; execution-policy semantics (close-entry fill evidence, strict-after SELL execution, resting conditional order semantics); Template / StrategyVersion theme-assignment representation before the Run snapshot and Template revision identification; parameter validation / ranges; engine / Run model (§18) |
| Review delta | S164-F01 (strict-after SELL wording, §10): CLOSED (recorded in docs/163). Template revision (2026-10-02): documentation only, pending independent review |

## 1. Evidence Labels

| Label | Meaning |
| --- | --- |
| COLLEAGUE SOURCE INTENT | The colleague's stated intent, recorded as given |
| HUMAN CONFIRMED | Explicitly confirmed by the Human (including colleague clarifications relayed and confirmed by the Human) |
| APPROVED ARCHITECTURE | Already HUMAN APPROVED in docs/160 / docs/161 |
| CURRENT REPOSITORY FACT | Verified in the repository or approved docs at the stated baseline |
| PRODUCT REQUIREMENT | A Human-requested product capability recorded at documentation level; no implementation authority |
| PROPOSED | A candidate interpretation; not approved |
| OPEN | Requires colleague clarification and / or a Human decision before READY |

Lifecycle terminology (CURRENT REPOSITORY FACT): a StrategyVersion is `DRAFT` / `ACTIVE` / `RETIRED` (ACTIVE and RETIRED are immutable; docs/160 §5, D-073, D-137); `READY` is a **Run** lifecycle state, at which the Run pins its selected StrategyVersion and its frozen configuration and universe (D-119). "READY" for this strategy requirement (§18) means the rules are complete enough to define an ACTIVE StrategyVersion that a READY Run could pin; it is not a StrategyVersion status.

## 2. Purpose

Capture the colleague-requested strategy faithfully, freeze the Human-confirmed rules (B-option close entry, sustained-below-MA condition, take-profit, stop-loss armed for the entire holding period, maximum holding period), record that every numeric rule is an editable **Strategy Template** parameter rather than a permanent constant, and list every rule that must still be decided before any Template-derived configuration can become an ACTIVE StrategyVersion pinned by a READY Run — without implementing anything and without forcing the strategy into an engine before architecture review (§14).

## 3. User-Provided Strategy Intent (COLLEAGUE SOURCE INTENT)

Original request, recorded as given; no condition is added.

| # | Intent |
| --- | --- |
| I-1 | Find a stock that has been closing below the 5-day moving average for approximately one week / a sustained recent period ("하락 마감"). |
| I-2 | On the signal day, the stock breaks above the 5-day moving average. |
| I-3 | Buy near / at that day's close. |
| I-4 | Require volume of at least 200% of the recent one-week average. |
| I-5 | On the following trading day, sell if price rises at least 5%. |

Colleague clarifications (HUMAN CONFIRMED, 2026-10-02):

| # | Clarification | Effect |
| --- | --- | --- |
| C-1 | Before entry, the close must have remained below its 5-day moving average for at least the configured number of consecutive trading days; default 5. The close is **not** additionally required to decline every day. | Closes the "하락 마감" ambiguity (§5) |
| C-2 | While a position is held, if the market price rises at least the configured percentage (default +5%) above the actual entry fill price → SELL signal. | Take-profit applies throughout the holding period; supersedes the D+1-only reading of I-5 (§10) |
| C-3 | Stop-loss is always active during the holding period (colleague wording: "continuous"): if a provider-verifiable price falls at least the configured percentage (default 5%) below the actual entry fill price → SELL signal. It does not wait until the last day. | §10 (recorded as: armed for the entire holding period, evaluated at every eligible monitoring observation; not tick-level) |
| C-4 | If neither take-profit nor stop-loss closes the position, it may be held for at most the configured number of trading days (default 10); on the final permitted trading day the position must be exited. Intent: if the expected move has not occurred within that period, the trade thesis is considered wrong or stale. | Closes the former "target not hit" OPEN item (§11) |
| C-5 | All numeric values (MA period, below-MA duration, volume threshold, take-profit, stop-loss, maximum holding) must be editable by the Human. | Strategy Template requirement (§19–§27) |

Still not stated by the source (therefore OPEN, §12): universe; exclusions; sizing; capital allocation; costs; price-limit / VI / halt behaviour; simultaneous-signal handling.

## 4. B-Option Entry — HUMAN CONFIRMED

**Frozen:** the final confirmed closing price of the signal day D is **not known** before the order decision. The strategy is therefore:

```text
PRE-CLOSE DECISION  →  CLOSING-AUCTION / CLOSE-ENTRY ATTEMPT
```

and **not**:

```text
final close confirmed  →  retroactive same-close buy      (FORBIDDEN)
```

Template parameter value: `entry_mode = PRE_CLOSE_CLOSE_ENTRY_B` (the only approved value; §20).

Conceptual flow (decision time and data sources OPEN, §8, §13):

| Step | When | Action |
| --- | --- | --- |
| 1 | At the approved pre-close decision time `decision_timestamp` | Evaluate whether the current provider-verifiable price satisfies the estimated / current MA breakout condition (basis OPEN, §7) |
| 2 | Same instant | Evaluate whether cumulative day volume at `decision_timestamp` satisfies the configured volume threshold (basis OPEN, §6) |
| 3 | Same instant | Evaluate the sustained-below-MA condition (`below_ma_consecutive_days`) from confirmed prior daily data (§5) |
| 4 | If all entry conditions hold | Record `PRE_CLOSE_SIGNAL` and create a close-entry **paper** order intent |
| 5 | Closing auction / close-entry process | Determine the actual paper fill **only** from provider-verifiable closing-auction / closing-price evidence under a future execution policy (§9); otherwise NO FILL |
| 6 | After the official close is available | Calculate the final MA relationship using the official D close |
| 7 | After step 6 | Record `POST_CLOSE_CONFIRMED = TRUE` or `FALSE` |

- `PRE_CLOSE_SIGNAL` is the decision record; it uses only information available by `decision_timestamp`.
- `POST_CLOSE_CONFIRMED` is an after-the-fact classification; it never changes the decision, the order, or the fill.
- **A `FALSE` post-close confirmation does not erase the trade.** If a fill occurred, the position and its outcome remain a real strategy outcome and are reported as such. What the strategy does next for a `FALSE` case is OPEN (Q-12.14).

## 5. Sustained-Below-MA Condition — HUMAN CONFIRMED

| Item | Content | Status |
| --- | --- | --- |
| Source phrase | "closing below the 5-day moving average for approximately one week / a sustained recent period" ("하락 마감") | COLLEAGUE SOURCE INTENT |
| Confirmed rule | `Close[t] < MA[t]` on each of at least `below_ma_consecutive_days` **consecutive** trading days before entry | HUMAN CONFIRMED (C-1) |
| Parameter | `below_ma_consecutive_days`; default **5**; examples 5, 10, 15, 20; editable Template parameter, not a permanent constant | HUMAN CONFIRMED (C-1, C-5) |
| MA period | `ma_period_days`; default **5**; editable Template parameter | HUMAN CONFIRMED (C-5) |
| Q-5.1 | Does "하락 마감" also require `Close[t] < Close[t-1]` every day? | **CLOSED — NO.** Only `Close[t] < MA[t]` is required |
| Q-5.2 | Length of "approximately one week" | **CLOSED** — "at least N consecutive trading days", N = `below_ma_consecutive_days`, default 5 |
| Q-5.3 | Strictly consecutive, or interruptions tolerated? | **CLOSED — consecutive** |
| Q-5.4 | Definition of `MA[t]` for prior days: simple average of the `ma_period_days` confirmed closes `t-(P-1) … t`? | OPEN (simple moving average of confirmed closes is the natural reading, not yet confirmed) |
| Q-5.5 | Must the qualifying run end on D-1 (the trading day immediately before the signal day), i.e. D-N … D-1 all satisfy the condition? | PROPOSED natural reading of "before entry" together with the D breakout (I-2); confirm before READY |

Data consequence (informational): with N = `below_ma_consecutive_days`, P = `ma_period_days` and the D-N … D-1 reading, evaluating `MA[t]` for D-N … D-1 needs confirmed closes from D-(N+P-1) through D-1 (D-9 … D-1 for the defaults 5 / 5). All of these are prior confirmed values and are known before D (no look-ahead).

## 6. Volume Condition — Threshold Confirmed, Basis OPEN

| Item | Content | Status |
| --- | --- | --- |
| Requirement | Signal-day volume ≥ `volume_threshold_percent` of the recent average volume | COLLEAGUE SOURCE INTENT — CAPTURED |
| Threshold parameter | `volume_threshold_percent`; default **200**; editable Template parameter (examples: 200, 250) | HUMAN CONFIRMED (C-5) |
| Lookback parameter | `volume_average_lookback_days`; editable Template parameter | Parameter HUMAN CONFIRMED as editable; **default OPEN** |
| Q-6.1 | Average over the prior N trading days, or over the calendar week's trading days? Default value of `volume_average_lookback_days`? | OPEN |
| Q-6.2 | Include or exclude the signal day D from the average? | OPEN |
| Q-6.3 | Signal-day volume at decision time is **cumulative intraday volume up to `decision_timestamp`**, not the full-day volume (the full-day volume, including the closing auction, is not known before the decision) | Follows from the B-option and anti-look-ahead rules (§4, §15) |
| Recommended clean candidate | `volume_average_lookback_days = 5`; `AVG_VOL = mean(Volume[D-5 … D-1])` (D excluded); entry requires `cumulative_volume[D, decision_timestamp] ≥ AVG_VOL × volume_threshold_percent / 100` | **PROPOSED, NOT YET HUMAN-APPROVED** |
| Q-6.4 | Whether the multiplier is a fixed rule or a configurable parameter | **CLOSED — configurable** (`volume_threshold_percent`) |

Note (informational, not a decision): comparing partial-day cumulative volume with full-day historical averages is stricter than a full-day comparison would be; whether that is the colleague's intent is part of Q-6.

## 7. MA Calculation at Pre-Close — OPEN

Because the D close is unknown at decision time, the MA value that includes D cannot be the official one at decision time. P = `ma_period_days` (default 5).

| Item | Content | Status |
| --- | --- | --- |
| `PRE_CLOSE_MA_BASIS` | How the MA is evaluated at `decision_timestamp` | **OPEN** |
| Possible future design (not frozen) | Estimated MA = (current provider-verifiable price at `decision_timestamp` + the P-1 prior confirmed daily closes D-1 … D-(P-1)) / P | PROPOSED candidate only |
| Breakout test under that candidate (informational) | `P_decision > MA_est`, which is algebraically equivalent to `P_decision > mean(Close[D-(P-1) … D-1])` (for P = 5: `mean(Close[D-4 … D-1])`) | Informational only |
| Alternative bases to consider | Compare against the prior day's official `MA[D-1]`; use a different reference price (for example last trade vs best quote) | OPEN |
| Post-close confirmation basis | `Close[D] > MA[D]` with the official close, computed after the official close (§4 step 6) | Defined for `POST_CLOSE_CONFIRMED`; exact comparison operator (`>` vs `≥`) OPEN |

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

## 10. Exit Rules — Take-Profit / Stop-Loss / Maximum Holding (HUMAN CONFIRMED)

The holding period starts when the entry paper fill exists. If there is no entry fill on D, there is no position and no exit monitoring.

### 10.1 Rules

| Rule | Content | Status |
| --- | --- | --- |
| Take-profit | While the position is open: `take_profit_target_price = entry_fill_price × (1 + take_profit_percent / 100)`. If an eligible provider-verifiable observation is `≥ take_profit_target_price` → **TAKE-PROFIT SELL SIGNAL**. Default `take_profit_percent = 5`; examples 3, 5, 7 | HUMAN CONFIRMED (C-2); configurable |
| Stop-loss | The stop-loss rule **remains armed for the entire holding period** — from the start of the holding period until the position is closed — and is evaluated at every eligible monitoring observation according to the configured monitoring granularity. It does not wait for the last holding day. `stop_loss_target_price = entry_fill_price × (1 − stop_loss_percent / 100)`. If an eligible provider-verifiable observation is `≤ stop_loss_target_price` → **STOP-LOSS SELL SIGNAL**. `stop_loss_percent` is a positive magnitude; default `stop_loss_percent = 5` (user meaning / display: −5%) | HUMAN CONFIRMED (C-3); configurable |
| Maximum holding period | If neither take-profit nor stop-loss has closed the position, it may be held for at most `max_holding_trading_days`; on the final permitted trading day the position **must** be exited → **TIME-EXIT SELL SIGNAL**. Default `max_holding_trading_days = 10`; examples 5, 10, 15, 20 | HUMAN CONFIRMED (C-4); configurable |
| Intent of the time exit | The trade thesis is considered wrong or stale if the expected move has not occurred within the maximum holding period | HUMAN CONFIRMED (C-4) |

`entry_fill_price` is the actual paper entry fill price from the verified close-entry fill (§9), never a fabricated or official-close substitute.

### 10.2 Conceptual Exit Model

```text
POSITION OPEN  (from the verified entry fill)
    |
    +-- eligible price <= entry_fill_price × (1 − stop_loss_percent / 100)
    |      → STOP-LOSS SELL SIGNAL
    |
    +-- eligible price >= entry_fill_price × (1 + take_profit_percent / 100)
    |      → TAKE-PROFIT SELL SIGNAL
    |
    +-- neither occurs
           → continue holding
           → until the final permitted trading day (max_holding_trading_days)
           → TIME-EXIT SELL SIGNAL
```

- Stop-loss and take-profit remain **armed for the entire holding period** and are evaluated at **every eligible monitoring observation** according to the configured monitoring granularity (granularity OPEN, Q-10.2). They are never deferred to the final holding day.
- **Tick-level continuous stop monitoring remains DEFERRED** (docs/160 §22, §34). No true tick-continuous stop monitoring or execution is implied; a price can cross a threshold and recover between eligible observations without a SELL signal being observed.
- The time exit applies **only** if the position is still open on the final permitted trading day.
- A single observation cannot satisfy both thresholds (stop target < entry < take-profit target). Precedence when both thresholds are crossed within one observation interval (for example a bar whose high and low straddle both targets) is OPEN (Q-10.3).
- Architecture placement: docs/160 §22 (HUMAN APPROVED §36 #7) assigns stop-loss / take-profit / max holding to the dedicated EXIT POLICY stage, after the model decision and before the risk gate; these rules are position-state rules, not market factors. How this strategy uses that stage is decided in architecture review (§14).

### 10.3 Execution — Strict-After Invariant (S164-F01)

| Item | Content |
| --- | --- |
| Execution | **Exit detection and SELL execution are separate moments** (strict-after, docs/160 §21). The observation that triggers a SELL signal is **not** the fill. After the SELL signal, a SELL order / intention is created; the paper execution then uses the **first eligible provider-verifiable price strictly after the SELL order exists**. **No fabricated exact +5.000% or −5.000% fill.** The eligible execution price may be above or below the target |
| Time exit | The TIME-EXIT SELL signal follows the same sequence: SELL order / intention, then strict-after paper fill or NO FILL + audit reason |
| Resting conditional order | A model in which a resting conditional (for example limit-at-target or stop) SELL order exists **before** the triggering price, so that the triggering print itself could be a valid fill, is **OPEN**. It applies only if a future execution policy explicitly models such an order; it is not assumed here |
| Monitoring truthfulness | Exits are evaluated only at eligible monitoring observations; the check is only as fine as the monitoring granularity (for example the docs/160 §22 15-minute slot-based exit check, "15분 기준" wording). A price can touch a target between checks without a SELL signal being observed. Tick-level continuous monitoring is DEFERRED (docs/160 §22, §34) |

Conceptual SELL sequence (S164-F01; applies to TAKE_PROFIT, STOP_LOSS and MAX_HOLDING_PERIOD):

```text
eligible provider-verifiable observation reaches a threshold
(take-profit / stop-loss), or the final permitted holding day is reached
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

### 10.4 Open Exit Details

| # | Question | Status |
| --- | --- | --- |
| Q-10.1 | Is `entry_fill_price` the raw fill price or the cost / slippage-adjusted price for target computation? | OPEN |
| Q-10.2 | Which price observation counts as "reaches" (trade print, bar high / low, slot price), and what monitoring granularity applies (for example the docs/160 §22 15-minute slot-based exit check)? | OPEN |
| Q-10.3 | Precedence if take-profit and stop-loss thresholds are both crossed within one observation interval | OPEN |
| Q-10.4 | Trading-day counting basis for `holding_trading_day_index` and the final permitted day (is the entry day D day 0 or day 1?) | OPEN |
| Q-10.5 | When during the final permitted trading day the time-exit SELL signal / order occurs (for example a slot, before the closing auction, at the close) | OPEN |
| Q-10.6 | Which sessions are eligible for exit monitoring and execution (regular session only, or also KRX after-hours sessions, docs/161 §18)? | OPEN |
| Q-10.7 | Handling when a SELL order receives NO FILL (re-attempt semantics), and when another exit condition triggers while a SELL order is pending | OPEN |

## 11. Former Open Exit Rule — Target Not Hit on D+1 — CLOSED

The original source did not specify what happens if D+1 never reaches +5%. **CLOSED** by colleague clarification (HUMAN CONFIRMED, C-2 – C-4):

- take-profit is active throughout the holding period, not only on D+1;
- stop-loss remains armed for the entire holding period and is evaluated at every eligible monitoring observation;
- if neither closes the position, it is exited on the final permitted trading day (`max_holding_trading_days`, default 10).

The other candidate choices previously listed here (exit at the D+1 close; hold until the target is reached; MA-based exit) are **not** selected. Remaining detail questions are in §10.4.

## 12. Other Open Strategy Questions

All OPEN unless already covered by canonical architecture (noted where applicable).

| # | Question | Status |
| --- | --- | --- |
| Q-12.1 | Universe: KOSPI, KOSDAQ, or both? (`Board` enum has `KOSPI`, `KOSDAQ`, `OTHER`; CURRENT REPOSITORY FACT) | OPEN |
| Q-12.2 | Exclusions: suspended issues, SPACs, ETF / ETN (`InstrumentType.ETP` exists), preferred shares (`InstrumentType.PREFERRED_STOCK` exists), others? | OPEN |
| Q-12.3 | Minimum price? | OPEN |
| Q-12.4 | Minimum liquidity (value traded / volume)? | OPEN |
| Q-12.5 | Behaviour at upper / lower daily price limits (entry and exit)? | OPEN |
| Q-12.6 | Gap-up behaviour on D (for example D opens already above the MA)? | OPEN |
| Q-12.7 | VI (volatility interruption) behaviour, including a VI during the decision window, the closing auction, or an exit? | OPEN |
| Q-12.8 | Trading-halt behaviour on D or during the holding period, including a halt on the final permitted holding day? | OPEN |
| Q-12.9 | Multiple simultaneous candidates: ranking / tie-break? | OPEN |
| Q-12.10 | Maximum number of entries per day? | OPEN |
| Q-12.11 | Position sizing? | OPEN (current DAILY sizing is a Run paper-policy allocation; reuse not decided) |
| Q-12.12 | Maximum open positions and capital allocation across positions? | OPEN |
| Q-12.13 | Transaction-cost and slippage values? | OPEN (cost inclusion required by docs/160 §36 #8; values not set) |
| Q-12.14 | If `POST_CLOSE_CONFIRMED = FALSE`: hold normally under §10 or apply a special exit? | OPEN |
| Q-12.15 | Same-stock re-entry rules (including re-entry after an exit within the same period)? | OPEN (docs/160 §20.1 pending-exposure invariant and "additional buy DISALLOW" apply to Intraday Runs; applicability to this hybrid strategy decided in architecture review) |
| Q-12.16 | Holiday / shortened-session handling for D and for holding-period trading-day counting (official calendar required, docs/160 §31) | OPEN |

## 13. Data Requirements (Conceptual)

No provider implementation decision is made here. N = `below_ma_consecutive_days`, P = `ma_period_days`, L = `volume_average_lookback_days`.

| Data | Use | Timing constraint |
| --- | --- | --- |
| Prior confirmed daily OHLCV (at least D-(N+P-1) … D-1, and the L-day volume window) | Sustained-below-MA condition; MA of prior days; average volume | Confirmed before D |
| Current-day cumulative volume at `decision_timestamp` | Volume trigger | `available_at ≤ decision_timestamp` |
| Pre-close provider-verifiable price at `decision_timestamp` | Breakout trigger, estimated MA | `available_at ≤ decision_timestamp` |
| Closing-auction / official close evidence for D | Paper fill (if policy-eligible); `POST_CLOSE_CONFIRMED` | After the close; never used for the D decision |
| Intraday / eligible observations from the entry fill through the final permitted holding day | Take-profit and stop-loss evaluation at every eligible monitoring observation; time exit; SELL execution | Strictly after the relevant fill / order / decision instants; session eligibility OPEN (Q-10.6) |
| Official exchange calendar | Trading-day counting for below-MA duration, volume lookback, and maximum holding period | Known before use (docs/160 §31) |

CURRENT REPOSITORY FACT (docs/161 §2, at `5d709b0`): the existing KIS current-price mapping includes current price and cumulative volume fields, and daily bars include OHLCV; no intraday time field is mapped and no closing-auction evidence is captured. Whether and how these are used is an architecture / provider decision, not made here.

## 14. Engine Intersection

This strategy is likely a **hybrid** of DAILY CONTEXT, INTRADAY / PRE-CLOSE EXECUTION, and multi-day position monitoring:

```text
Daily history (confirmed closes / volumes)
  → sustained-below-MA candidate state                      (DAILY CONTEXT)

Pre-close intraday observation at decision_timestamp
  → breakout + volume trigger → PRE_CLOSE_SIGNAL            (INTRADAY / PRE-CLOSE)

Closing-entry execution policy
  → provider-verifiable closing evidence → paper fill / NO FILL

Official close
  → POST_CLOSE_CONFIRMED = TRUE / FALSE                     (classification only)

Holding period (up to max_holding_trading_days)
  → stop-loss / take-profit armed; evaluated at every
    eligible monitoring observation                         (EXIT POLICY, docs/160 §22)
  → final permitted trading day → time exit
  → SELL signal → SELL order → strict-after paper fill / NO FILL
```

Why neither existing nor planned engine fits as-is (CURRENT REPOSITORY FACT / APPROVED ARCHITECTURE):

- DAILY (docs/160 §2): evaluation happens after the session (07:00 KST next-day Auto slot), the only execution policy is `NEXT_TRADING_DAY_OPEN`, and the only signal metric is `DAILY_CHANGE_PCT`. It cannot express a pre-close decision with a same-day close entry or intraday stop-loss evaluation during the holding period.
- INTRADAY_15M (docs/160 §3, §11, §21): built around a repeated 15-minute slot grid, and a Run is either DAILY or INTRADAY_15M for its whole life. A single pre-close decision plus closing-auction execution plus a multi-day exit-policy holding period is not yet modelled.

**Do not force this strategy into the DAILY engine or the new Intraday engine until architecture review.** The engine / Run model choice is an OPEN architecture decision.

## 15. Anti-Look-Ahead Invariant (HARD RULE)

- No final D close value (official close, final daily volume, final daily high / low, final MA including D) may be used to make a decision that claims to have occurred before the D close.
- No retrospective same-close synthetic fill.
- Decision evidence uses only information with `available_at ≤ decision_timestamp`.
- `POST_CLOSE_CONFIRMED` is computed only after the official close and never feeds back into the D decision, order, or fill.
- Exit signals use only observations available at the time of the signal; no exit is back-dated to a price that was not observed in time.
- Strategy parameters used by a Run are fixed in the immutable ACTIVE StrategyVersion that the Run pins at READY; editing a Template never changes the parameters, signals, orders, or fills of an existing StrategyVersion or Run (§24).
- This aligns with the existing BJStock point-in-time rules (docs/160 §12: only inputs available by the decision cutoff are eligible; no retroactive trades, docs/160 §29–§30; strict-after execution, docs/160 §21).

## 16. Audit / Security Requirements

The strategy must eventually audit, through the canonical BJStock domain / audit architecture (`trade_audit_logs`, `operational_events`, `orders`, `executions`, and the docs/163 integrity layer, which commits to those records):

| Record | Content |
| --- | --- |
| Template provenance | `template_id`; Template version / revision reference at StrategyVersion creation |
| StrategyVersion | StrategyVersion id |
| Effective parameter snapshot | `ma_period_days`, `below_ma_consecutive_days`, `volume_average_lookback_days`, `volume_threshold_percent`, `take_profit_percent`, `stop_loss_percent`, `max_holding_trading_days` (and `entry_mode`) as frozen in the ACTIVE StrategyVersion pinned by the READY Run |
| Candidate qualification | Sustained-below-MA evaluation inputs and result |
| Decision timestamp | `decision_timestamp` (signal timestamp) |
| Available input timestamps | `available_at` / `received_at` of every input used |
| MA basis | Basis identifier and inputs used (§7) |
| Volume basis | Average definition, window, cumulative volume at decision (§6) |
| `PRE_CLOSE_SIGNAL` | Decision record with reason codes |
| Order intent | Paper close-entry order and its creation time |
| Fill / no-fill | Fill evidence (source, time, venue) or NO FILL with reason |
| Entry fill price | `entry_fill_price` |
| Official closing confirmation | Official close value and its source / time |
| `POST_CLOSE_CONFIRMED` | TRUE / FALSE with the computation inputs |
| Exit targets | `take_profit_target_price` and `stop_loss_target_price` with their derivation from the verified fill |
| Holding day | `holding_trading_day_index` at each exit evaluation / signal |
| Exit signal | SELL signal timestamp, the observation / decision source that triggered it, and `exit_reason` = `TAKE_PROFIT` / `STOP_LOSS` / `MAX_HOLDING_PERIOD` (or another future approved reason) |
| SELL order | SELL order / intention and its creation timestamp |
| Eligible execution observation | The provider-verifiable observation used for the SELL fill, with its time (strictly after the SELL order) |
| Execution evidence | Actual paper fill (source, time, price), or NO FILL with reason |
| Reason codes | From the central reason-code registry (docs/163 §45) |

**No separate strategy-specific audit ledger** is created (docs/160 §0.2; docs/163 §8.1).

## 17. Future Backtest Warning

- A backtest must **not** simulate the B option as "confirmed D close → same D close buy"; that introduces look-ahead.
- Historical B-option research requires a data source / approximation that represents the actual pre-close decision point (price and cumulative volume as of `decision_timestamp`) and the closing execution semantics.
- If such data is unavailable, any backtest must be explicitly labelled as an approximation (for example "OPTIMISTIC CLOSE-ENTRY APPROXIMATION"), stored apart from Forward Test evidence, and never presented as live-equivalent performance (same principle as docs/160 §21 for offline analytical references).
- Bar-based backtests must not assume exact +X% / −X% fills, and must label how they resolve bars that cross both exit thresholds (Q-10.3).
- Results from trying many Template parameter combinations are research output, not Forward Test evidence, and must not be presented as validated performance of any single StrategyVersion.

## 18. Status

**Strategy Requirement Status: DRAFT.**

Not READY because at least these remain OPEN:

1. Pre-close MA basis (§7) and the prior-day MA definition (Q-5.4); confirmation that the qualifying below-MA run ends on D-1 (Q-5.5).
2. Volume averaging semantics: `volume_average_lookback_days` default, averaging window, inclusion of D (§6, Q-6.1, Q-6.2).
3. Exact entry decision time (§8).
4. Holding-period details: raw vs cost-adjusted entry basis, observation type, threshold precedence, trading-day counting, time-exit timing, session eligibility, SELL no-fill handling (§10.4, Q-10.1 – Q-10.7).
5. Universe / exclusions / minimum price / liquidity (Q-12.1 – Q-12.4).
6. Price-limit, VI, and trading-halt handling (Q-12.5, Q-12.7, Q-12.8).
7. Simultaneous-signal handling, maximum entries / positions, position sizing, capital allocation (Q-12.9 – Q-12.12).
8. Transaction-cost and slippage values (§9, Q-12.13).
9. `POST_CLOSE_CONFIRMED = FALSE` handling (§4, Q-12.14).
10. Execution-policy semantics: close-entry fill evidence (§9), strict-after SELL execution, and resting conditional order semantics (§10.3).
11. Template / StrategyVersion theme-assignment representation before the Run-level snapshot (§23; the Run-level DRAFT-copy / READY-freeze rule is already approved) and Template revision identification (§24).
12. Template parameter validation / ranges (§26).

Also required before READY: the engine / Run model decision (§14).

Closed by the 2026-10-02 colleague clarifications: the meaning of "5일선 아래로 하락 마감"; the default below-MA duration; the take-profit behaviour; the stop-loss behaviour; the maximum holding period; the final-day exit requirement; configurability of the volume multiplier (Q-6.4).

No StrategyVersion creation. No implementation. No real trading.

## 19. Strategy Template Model — PRODUCT REQUIREMENT

The strategy is a **configurable Strategy Template**, not a single fixed rule set.

- Template family: `MA5_BREAKOUT_VOLUME_CLOSE_ENTRY_TEMPLATE`. "MA5" names the family's default; `ma_period_days` is editable.
- A **Template** is a saved, editable Human configuration of the strategy parameters (§20) plus a target assignment (§23).
- The rule structure (§4 – §10) is shared by every Template of the family; Templates differ only in parameter values and targets.
- A Template is **not** a StrategyVersion and is never itself executed; a future activation derives a StrategyVersion from it, which is immutable once ACTIVE and can be pinned by a READY Run (§24).
- Persistence schema, entity design, and UI implementation are **not** defined here (§27).

## 20. Template Editable Fields

Conceptual fields; names are conceptual, not a schema.

| Field | Meaning | Default | Status |
| --- | --- | --- | --- |
| `template_name` | Human-readable Template name | — | PRODUCT REQUIREMENT |
| `ma_period_days` | Moving-average period (trading days) | 5 | HUMAN CONFIRMED default; editable |
| `below_ma_consecutive_days` | Minimum consecutive trading days with `Close[t] < MA[t]` before entry (§5) | 5 | HUMAN CONFIRMED default; editable |
| `volume_average_lookback_days` | Volume averaging window (trading days) (§6) | **OPEN** (PROPOSED candidate 5, not approved) | Editable; default and semantics OPEN |
| `volume_threshold_percent` | Required signal-day volume as % of the average (§6) | 200 | HUMAN CONFIRMED default; editable |
| `take_profit_percent` | Take-profit distance above `entry_fill_price`, positive magnitude (§10) | 5 (displayed as +5%) | HUMAN CONFIRMED default; editable |
| `stop_loss_percent` | Stop-loss distance below `entry_fill_price`, positive magnitude; armed for the entire holding period (§10) | 5 (displayed as −5%) | HUMAN CONFIRMED default; editable |
| `max_holding_trading_days` | Maximum holding period; exit on the final permitted trading day (§10) | 10 | HUMAN CONFIRMED default; editable |
| `entry_mode` | Entry mechanism (§4) | `PRE_CLOSE_CLOSE_ENTRY_B` | HUMAN CONFIRMED; the only approved value |
| `target_type` | `STOCK` (one or more stocks) / `THEME` (§23) | — | PRODUCT REQUIREMENT |
| target reference | The stock(s) or theme the Template is assigned to (§23) | — | PRODUCT REQUIREMENT |

All numeric fields are Human-editable. Validation rules and allowed ranges are future design work (§26).

Percent sign convention (product / validation semantics only; no persistence implementation):

| Parameter | Configured value | User meaning / display | Formula |
| --- | --- | --- | --- |
| `take_profit_percent` | Positive magnitude, e.g. `5` | +5% from entry fill | `take_profit_target_price = entry_fill_price × (1 + take_profit_percent / 100)` |
| `stop_loss_percent` | Positive magnitude, e.g. `5` — **never** `-5` | −5% from entry fill | `stop_loss_target_price = entry_fill_price × (1 − stop_loss_percent / 100)` |

Because the stop-loss formula already subtracts the value, a configured `-5` would be contradictory (it would place the stop above the entry price) and is not a valid representation.

## 21. Default Colleague Template — HUMAN-CONFIRMED BASELINE

```text
DEFAULT COLLEAGUE TEMPLATE

MA:                         5-day moving average
Below-MA consecutive days:  5 trading days   (Close[t] < MA5[t], consecutive)
Volume threshold:           200%             (averaging basis OPEN, §6)
Entry:                      B-option pre-close decision / close-entry attempt
Take profit:                +5% from actual entry fill
Stop loss:                  −5% from actual entry fill (stop_loss_percent = 5),
                            ARMED FOR THE ENTIRE HOLDING PERIOD,
                            evaluated at every eligible monitoring observation
Maximum holding:            10 trading days
Time exit:                  exit on the final permitted trading day
                            if the position is still open
Editable:                   YES — all numeric values are Template parameters
```

This baseline is the Human-confirmed default parameter set. It is still **not** READY (§18).

## 22. Template Examples — NON-AUTHORITATIVE

Product examples only. They illustrate how independently configured Templates could look; **they are not approved strategy rules**, create no StrategyVersion, and authorize no Run.

| Template | Target | MA | Below-MA duration | Volume | Take profit | Stop loss | Max holding | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A — 삼성전자 | Stock: 삼성전자 | 5 | 5 trading days | 200% | +5% | −5% | 10 trading days | Uses the default colleague values (§21) |
| B — HBM | Theme: HBM | 5 | 10 trading days | 250% | +7% | −5% | 15 trading days | **EXAMPLE numbers only** (possible user edit) |
| C — 방산 | Theme: 방산 (Defense) | — | — | — | — | — | — | **EXAMPLE only**: values configured independently by the Human; no numbers recorded |

## 23. Template Assignment — PRODUCT REQUIREMENT

A Template may eventually be assigned to:

- an individual stock (example: Template A → 삼성전자);
- a selected set of stocks;
- a theme (examples: Template B → HBM theme; Template C → Defense theme).

Theme membership rule — the existing Run-level invariant remains **authoritative** (APPROVED ARCHITECTURE docs/160 §7, HUMAN APPROVED §36 #14; D-132, D-133, D-119):

- Theme members are copied into the **Run universe while the Run is DRAFT**, with provenance captured at the same copy instant (source theme id, theme name at copy, copied member set, copy time).
- At **READY** the Run universe and its provenance record are frozen; READY does not re-resolve the live theme.
- Later edits to the live theme membership change only the live watchlist and must **not** rewrite the universe or the historical experiment conditions of an existing READY Run.
- This Run snapshot timing is already approved and is **not** reopened here.

OPEN (limited scope): how a Template's or StrategyVersion's theme assignment is represented **before** the Run-level snapshot (for example whether it holds only a theme reference that the Run DRAFT copy resolves). This does not affect the Run-level rule above.

## 24. Template vs StrategyVersion

| Concept | Nature |
| --- | --- |
| Template | Editable Human configuration (parameters + target assignment) |
| StrategyVersion | Experiment definition containing the full effective parameter snapshot and a reference to the Template (and Template revision) it was derived from; **immutable once ACTIVE** (lifecycle `DRAFT` / `ACTIVE` / `RETIRED`) |
| READY Run | Pins the selected ACTIVE StrategyVersion and its frozen configuration (and its frozen universe, §23) |

Invariant: **editing a Template MUST NOT mutate an ACTIVE StrategyVersion, nor the StrategyVersion or configuration pinned by a READY Run.**

Example:

```text
Template A (initial):   5 / 200 / +5 / −5 / 10
    (below-MA days / volume % / take profit / stop loss / max holding;
     stop loss shown as user meaning, configured as stop_loss_percent = 5)
        → activation → StrategyVersion A-v1 (ACTIVE, immutable)
        → a READY Run pins A-v1 and its frozen configuration

Human later edits Template A:   10 / 250 / +7 / −4 / 15
        → a future activation → StrategyVersion A-v2 (ACTIVE, immutable)

A-v1 remains unchanged; READY Runs that pinned A-v1 keep A-v1 parameters.
```

- The ACTIVE StrategyVersion's own parameter snapshot is authoritative for the Runs that pin it; the Template reference is provenance only.
- How Template revisions are identified and how activation creates a StrategyVersion are OPEN implementation details.

## 25. Run / Paper Account Isolation

Preserves APPROVED ARCHITECTURE (docs/160 §6, Option A — Run per Model): each Strategy Run owns one StrategyVersion and an **independent** paper account, cash, and performance history.

- Samsung (Template A), HBM (Template B), and Defense (Template C) Runs can therefore be evaluated independently.
- No parameter change is shared across Runs, and no parameter change is applied retroactively to an existing Run.
- The same stock may appear in several Runs under different Templates; each Run keeps its own account and evidence.

## 26. Future Template Form — UI CONCEPT (NOT IMPLEMENTED)

Conceptual form only; no Compose UI is authorized.

```text
전략 템플릿 이름     [                  ]
이동평균선           [ 5 ] 일
MA 아래 유지기간     [ 5 ] 거래일
거래량 평균기간      [   ] 거래일
거래량 기준          [ 200 ] %
익절 목표            [ 5.0 ] %      매수가 대비 +5.0%
손절 기준            [ 5.0 ] %      매수가 대비 -5.0%
최대 보유기간        [ 10 ] 거래일
적용대상             [ 종목 / 테마 ]
진입방식             [ B안 — 장마감 전 판단 ]
```

- All numeric fields are editable.
- 익절 목표 / 손절 기준 are entered as positive magnitudes; the helper text shows the signed meaning relative to the entry price (§20 sign convention).
- The volume averaging period has no approved default (§6).
- Validation rules, allowed ranges, and step sizes are future design work (OPEN).

## 27. No-Implementation Boundary

This document authorizes **none** of the following:

- a Template Room entity, schema, or migration;
- Compose UI for Templates;
- StrategyVersion creation;
- runtime signal logic;
- take-profit, stop-loss, or time-exit execution;
- theme assignment implementation;
- any provider, scheduler, probe, device, or credential change.

No code. ImplementationAuthority: NONE.

### 27.1 UI Integration Note (2026-10-11)

§26 and §27 above, and the header's "authorizes no … Compose UI" wording, are kept unchanged as the authorization state at the time they were written.

As of the UI integration at main merge commit `a2031deed497ae1ac793bbd744b2ccd3db193972` (`feature/ui-strategy-admin-demo` at `69bd205`), the Human authorizes a **non-persistent Strategy Template Preview UI** (전략 템플릿 (미리보기), docs/165 §M). The authorization is limited to demo / preview behavior:

- displaying the template preview;
- in-memory demo templates;
- read-only instrument lookup and read-only theme lookup;
- a disabled, locked create action (전략 버전 만들기).

It does **not** authorize:

- persistence (no Template Room entity, schema, or migration);
- StrategyVersion creation;
- template activation;
- snapshot creation;
- Run creation;
- runtime execution (signal logic; take-profit, stop-loss, or time-exit execution).

This note supersedes only the prior "no preview UI authorized" state, and only from this integration point onward. It is not retroactive authorization. Every other §27 item stays unauthorized, and ImplementationAuthority for the strategy and its Template runtime remains NONE.

---

PAPER TRADING ONLY. No real brokerage order capability. KIS `/trading/` remains forbidden. ImplementationAuthority: NONE.
