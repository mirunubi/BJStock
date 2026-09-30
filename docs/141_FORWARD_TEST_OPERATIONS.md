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
4. App schedules **one** wake-up for the next 07:00 Asia/Seoul strictly after now (for example ON at 06:00 or 06:59:59 → today 07:00; ON at 07:00, 07:00:01, or 15:00 → tomorrow 07:00). Nothing runs immediately when Auto is turned ON.

Each wake-up is a one-time WorkManager request (network connected) named after its slot, e.g. `bjstock_forward_test_auto_2026-10-01_0700_KST`, and schedules the following day's slot before it starts working. Every calendar day has a slot; weekends and holidays simply find no new market date.

07:00 KST is the canonical daily Auto target. It remains an earliest eligible target, not an exact execution guarantee: WorkManager may run later (Doze, battery, no network). A late wake-up keeps its slot identity (`auto:2026-10-01:0700:KST`), processes market dates through the actual cutoff (catch-up by market date, not worker timestamp), and schedules only the next future slot — missed days are not replayed as a burst.

Builds before the 07:00 target (D-168) scheduled 07:30 slots (`…_0730_KST`). Such leftover work is not a valid current slot: it never runs a Forward Test and does not prevent the 07:00 slot from being scheduled. Turning Auto OFF and ON again cancels it and schedules the next 07:00 slot.

## Auto OFF

Turning Auto OFF cancels every pending Auto wake-up. A wake-up that races with OFF sees Auto OFF and exits without running. Run Now and Retry Failed Cycle keep working.

## Legacy periodic work

Builds before Phase 11 / Gate 7B registered periodic work `bjstock_forward_test_v1`. Every app start and every Auto toggle cancels it. If it still fires once, it does not run a Forward Test and does not create an operation; with Auto ON it only makes sure the new daily slot exists.

## Schedule status

`ForwardTestScheduler.status()` reports Auto ON / OFF, the next slot id and time, its WorkManager id and state, and the last scheduling failure. The Operations UI that will show it is not built yet. Schedule changes are recorded as `WORKER_SCHEDULE_CHANGED` operational events (`docs/150` 20.9.9).

## Run Now

Manual button on Forward Test screen. Computes current `throughDate` (Seoul 18:00 cutoff) and runs `ForwardTestOrchestrator` catch-up for READY/RUNNING runs.

Works even when Auto is OFF.

Run Now, the Worker, and Retry Failed Cycle share one execution guard. If another Forward Test operation is already running, the new request does not execute and shows `ALREADY_RUNNING`; each request is recorded in `forward_operations` (`docs/150` 20.4).

## Interrupted Operation (App Killed)

If Android kills the app while a Forward Test operation is running, its `forward_operations` row is left `RUNNING`. On the next app start, before the Auto schedule is re-applied, BJStock closes every `RUNNING` row that was started before the current process started (strict process-start cutoff; rows of the current process are never touched):

```text
status       FAILED
final_code   PROCESS_INTERRUPTED
message      Operation was interrupted before completion and recovered on app start
elapsed_ms   (empty — real duration unknown)
```

- One `OPERATION_FINISHED` event is appended in the same transaction; earlier events of that operation stay as they were. A cycle that was started but never finished is not marked finished by recovery.
- Recovery does not run a Forward Test by itself. Market dates not yet processed are picked up by the next Worker run or Run Now through the normal catch-up.
- If WorkManager redelivers the interrupted Worker attempt, it gets `retry`; the next attempt runs normally under the same slot with the next attempt number (`worker:auto:2026-10-01:0700:KST:1`). A later Run Now is unaffected.
- Recovery is idempotent: restarting the app again changes nothing.
- Recovery does not change the Auto schedule; after recovery the app re-applies Auto (existing pending slot kept, otherwise the next slot is created).

Details: `docs/150` 20.8 and 20.9.

Future Operations UI requirement: a `RUNNING` operation that already has `OPERATION_FINISHED` must be shown as a high-priority local data-integrity warning (not an API error log entry). Not built yet (`docs/150` 20.8.11).

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
Actual WorkManager wake-up (07:00 KST daily slot): DEFERRED
Actual KIS daily automation: DEFERRED
Actual offline catch-up on device: DEFERRED
```

Unit/Robolectric coverage validates orchestrator semantics offline via `LocalOnlyMarketDataGateway`.
