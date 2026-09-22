# Forward Test Operations

How to run a long forward test (months/year) on device after Physical Device Runtime Gate.

## Defaults

```text
AUTO SCHEDULER DEFAULT = OFF
```

Do not enable Auto Forward Test until physical-device acceptance for WorkManager + KIS daily sync is complete.

## Auto ON

1. Configure KIS credentials
2. Create Strategy Run (DRAFT) → add universe instruments → Prepare History → mark READY
3. On Run detail: **Auto Forward Test → ON**
4. App registers unique periodic work `bjstock_forward_test_v1` with network connected

WorkManager may run late. Catch-up still applies by market date, not worker timestamp.

## Run Now

Manual button on Forward Test screen. Computes current `throughDate` (Seoul 18:00 cutoff) and runs `ForwardTestOrchestrator` catch-up for READY/RUNNING runs.

Works even when Auto is OFF.

## Status Labels

| Status | Meaning |
|--------|---------|
| UP TO DATE | Last COMPLETE ≥ latest available market date through cutoff |
| CATCHING UP | Missing market dates remain before throughDate |
| WAITING FOR MARKET DATA | No trade dates to process yet |
| FAILED | Oldest cycle FAILED (see error) |
| BLOCKED | Non-retryable failed cycle prevents progress |

## Blocked Cycle

Example dashboard:

```text
Forward Test Blocked
2026-10-14
SNAPSHOT_MISSING_PRICE
```

Worker will not advance past this date. Fix data/policy, then **Retry Failed Cycle**.

## Retry Failed

Always retries the oldest FAILED market date first. Successful prior stages are not duplicated.

## Credential Error

`AUTH_REQUIRED` — configure KIS credentials. No market sync, factors, evaluations, or trades.

## Data Error

Examples: `SNAPSHOT_MISSING_PRICE`, `DATA_INTEGRITY_ERROR`, `INSUFFICIENT_WARMUP_DATA`.

Non-retryable failures stay visible on the dashboard until fixed + Retry.

## Phone Offline Recovery

1. Device was off / offline for days
2. Next successful Worker or Run Now
3. Universe daily bars sync incrementally through `throughDate`
4. Missing market dates process Monday → Tuesday → Wednesday ASC
5. First FAILED date still blocks later dates

## Physical Device Pending

Until Phase 10 acceptance:

```text
Actual WorkManager wake-up: DEFERRED
Actual KIS daily automation: DEFERRED
Actual offline catch-up on device: DEFERRED
```

Unit/Robolectric coverage validates orchestrator semantics offline via `LocalOnlyMarketDataGateway`.
