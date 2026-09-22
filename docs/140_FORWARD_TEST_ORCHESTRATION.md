# Forward Test Orchestration

Phase 9 long-term forward-test pipeline. Emphasizes **order**, **reproducibility**, **idempotency**, **catch-up**, and **look-ahead blocking** over exact wall-clock execution time.

## Universe

Each Strategy Run has an explicit Forward Test Universe in `strategy_run_instruments`.

- Minimum 1 instrument to reach READY
- Editable only while `DRAFT`
- Immutable after `READY` / `RUNNING` / `PAUSED` / `COMPLETED` / `CANCELLED`
- Full KOSPI/KOSDAQ master is never auto-enrolled

Change universe → create a new Strategy Run.

## Daily Pipeline Order (per market date D)

1. Pending order fills (`asOfMarketDate = D`)
2. Factor calculation (universe instruments with a D bar; strategy-enabled factors only)
3. Strategy evaluation (`evaluationDate = D`)
4. New pending order creation (BUY/SELL → `PENDING_EXECUTION`, never same-day fill)
5. Portfolio snapshot
6. Cycle `COMPLETE` (requires snapshot)

Market data sync for the universe runs **before** cycle creation (not a cycle stage).

## Catch-up

After sync, cycles are built for distinct local trade dates:

`last COMPLETE date` exclusive → `throughDate` inclusive, **ASC**.

- Weekend / holiday with no bars → no fake cycles
- Phone offline for days → next wake-up processes missed market dates in order
- First non-retryable `FAILED` date blocks later dates

## Cycle States / Stages

Statuses: `PENDING` | `RUNNING` | `COMPLETE` | `FAILED`

Stages: `PENDING_FILLS` → `FACTORS` → `EVALUATIONS` → `ORDER_CREATION` → `SNAPSHOT` → `COMPLETE`

Unique key: `(strategy_run_id, market_date)`

Retry increments `attempt_count`. Successful side effects stay idempotent; resume continues at the next needed stage.

## Look-ahead Protection

- Factors / evaluations use existing `asOfDate` future-data guards
- Pending fills use `processPendingOrders(runId, asOfMarketDate)`:
  - next bar with `signalDate < trade_date <= asOfMarketDate` only
- Future bars already in Room cannot fill early (Future-Bar Poison)

## End-Date Policy

On `run.end_date = D`:

- Prior pending may fill at D open
- Factor + evaluation for D allowed
- **No new pending orders** from D signals (`allowNewOrders = false`)
- Residual `PENDING_EXECUTION` → `CANCELLED` (history kept)
- Open positions **not** force-liquidated; final snapshot marks to market at D close

## WorkManager

- Unique periodic work `bjstock_forward_test_v1`
- Network: `CONNECTED`
- Auto default: **OFF**
- No `SCHEDULE_EXACT_ALARM` / AlarmManager
- Worker only wakes up and delegates to `ForwardTestOrchestrator`
- Manual **Run Now** uses the same orchestrator

## Operational Cutoff

`throughDate` uses **Asia/Seoul** independent of device timezone:

- before 18:00 → yesterday
- from 18:00 → today
- then `min(calculated, run.end_date)`

18:00 is a BJStock operational assumption that daily bars are ready — not exchange law.

## AI Advisory

Not part of the automatic forward pipeline. Never blocks cycles.
