# BJStock Decision Log

## D-001

BJStock is an Android native app.

## D-002

The MVP does not place real orders.

## D-003

The MVP purpose is paper trading forward test.

## D-004

The virtual account is managed by BJStock itself.

## D-005

Strategy uses a factor + weight + score structure.

## D-006

AI is an optional advisor.

## D-007

Docker PostgreSQL is not the runtime DB.

## D-008

The Android runtime DB uses Room.

## D-009

Live trading is a future phase.

## D-010

DB timestamps are stored in UTC and displayed as Asia/Seoul in the UI.

## D-011

PostgreSQL ENUM is not used. Status vocabularies use TEXT + CHECK.

## D-012

The independent forward-test unit is `strategy_runs`.

## D-013

Position is a current projection. Executions are the trading-history source of truth.

## D-014

Financial numbers do not use floating point. Use NUMERIC.

## D-015

AI is not a trading actor. AI output is stored as advisory history.

## D-016

FK delete default is RESTRICT. The only CASCADE is `stock_evaluation_details` when its parent evaluation is deleted.

## D-017

There is no `trading_accounts` table. Virtual cash and holdings belong to a `strategy_runs` row.

## D-018

`factor_definitions.value_type` is a closed TEXT + CHECK code list: NUMBER, PERCENT, RATIO, CURRENCY, COUNT.

## D-019

`strategy_runs.run_type` currently allows PAPER only. LIVE is not pre-declared.

## D-020

AI advice request and result are 1:1. Multiple opinions are recorded as multiple requests.

## D-021

Android Application ID is `com.mirunubi.bjstock`.

## D-022

The Android runtime database is Room. The app does not connect to PostgreSQL.

## D-023

Room database version 1 is based on the Phase 1 PostgreSQL business model. Phase 3-D raises Room to version 2 for instrument master columns.

## D-024

Room schema export JSON is kept in Git.

## D-025

Room runtime does not include the laboratory `schema_migrations` table.

## D-026

Kotlin enums are stored as String codes, never ordinals.

## D-027

Financial amounts do not use floating point at runtime. Room stores KRW as Long won and scaled Long for ratios.

## D-028

Android display timezone is Asia/Seoul. Storage is UTC.

## D-029

KIS App Key / App Secret are not stored in Room.

## D-030

KIS secrets are encrypted with an Android Keystore AES key and stored in app-private storage.

## D-031

Deprecated EncryptedSharedPreferences / MasterKey APIs are not used for new work.

## D-032

KIS access tokens are treated as secrets and stored encrypted.

## D-033

KIS tokens are reused until expiry minus a safety margin.

## D-034

Phase 3-A does not store brokerage account information.

## D-035

Phase 3-A connection test succeeds when OAuth token issuance succeeds. No market-data API is required.

## D-036

Broker order APIs are not part of the current architecture.

Phase 3-A spec numbered those eight decisions as D-027–D-034. D-027 and D-028 were already assigned in Phase 2, so they are recorded as D-029–D-036.

## D-037

Phase 3-B allows only KIS quotations APIs.

## D-038

`/trading/` endpoints are blocked by an architecture guard.

## D-039

Current-price lookup uses inquire-price.

## D-040

Daily bars use inquire-daily-itemchartprice.

## D-041

Phase 3-B does not persist market data to Room.

## D-042

KIS HTTP 200 is not treated as KIS business success.

## D-043

The market repository returns daily bars in trade-date ascending order.

## D-044

Long-range automatic backfill is deferred to Phase 3-D.

## D-045

Market daily data is stored only for an existing instrument.

## D-046

Instruments are not auto-created from market-data lookups.

## D-047

Daily market history is stored as adjusted prices.

## D-048

Re-collected daily bars are UPSERTed.

## D-049

`INSERT OR REPLACE` is not used for daily bars, so existing row ids stay stable.

## D-050

One API daily-bar batch is persisted in a single Room transaction.

## D-051

Current-price snapshots are not stored in Phase 3-C.

## D-052

Instrument master download is deferred to Phase 3-D.

## D-053

KRX and KOSPI/KOSDAQ boards are separate. `market` is not a listing board.

## D-054

Domestic listed instruments use `market=KRX`.

## D-055

`board` is KOSPI, KOSDAQ, or OTHER.

## D-056

Instrument master uses the official KIS MST zip files, not the OAuth market-data REST API.

## D-057

Master sync is UPSERT plus inactive. Rows are not deleted.

## D-058

An incomplete master must not deactivate existing instruments.

## D-059

Historical daily sync uses 90 calendar-day inclusive chunks.

## D-060

A long-range historical sync persists only after every network chunk succeeds.

## D-061

Historical daily sync stores ADJUSTED prices only.

## D-062

Whole-market historical backfill is forbidden in this phase.

## D-063

The Factor Engine reads only local Room market data. It does not call KIS.

## D-064

Factor calculation must not read market bars after `asOfDate`.

## D-065

A missing factor is not a zero score. `NO_DATA`, `INSUFFICIENT_HISTORY`, and `INVALID_DATA` are not stored.

## D-066

The initial system catalog is six market-data factors: PRICE_VS_MA20, PRICE_VS_MA60, MOMENTUM_20D, MOMENTUM_60D, VOLATILITY_20D, VOLUME_RATIO_20D.

## D-067

Raw calculation and 0..100 normalization are separate steps.

## D-068

Normalized factor scores are in 0..100.

## D-069

`calculation_version` is preserved. Re-running the same version UPSERTs; a new version inserts a new row.

## D-070

Factor outputs are values only. They do not create BUY/HOLD/SELL decisions.

## D-071

Financial-statement factors are not implemented until a trusted data source is chosen.

## D-072

Strategy versions explicitly pin each enabled factor's calculation version.

## D-073

ACTIVE strategy-version thresholds, enabled factors, weights, calculation versions, and gates are immutable. Changes require a new DRAFT version.

## D-074

Enabled factor weights use the existing scaled-integer policy and must sum exactly to 100%.

## D-075

A missing enabled factor is not replaced with zero and remaining weights are not renormalized.

## D-076

Factor min/max scores are inclusive eligibility gates, not weighted-score clamps.

## D-077

BUY, HOLD, and SELL use inclusive buy/sell thresholds. A failed factor gate produces NO_ACTION.

## D-078

Forward evaluations are immutable snapshots. A duplicate run/instrument/date is not overwritten.

## D-079

Preview evaluation does not persist evaluation rows and may evaluate a DRAFT strategy version.

## D-080

While AI is unused, `ai_score` is NULL and final score/decision equal the quant result.

## D-081

Phase 5 BUY/SELL decisions do not create orders, executions, or positions.

## D-082

Each strategy run is one independent virtual account.

## D-083

Orders plus executions are the trade-history source of truth. Positions are a current projection.

## D-084

Virtual cash is managed through an append-only `cash_ledger`. Current cash is the latest `balance_after`.

## D-085

Paper fills never use the signal-day close. They use the next available trading-day open.

## D-086

Phase 6 forbids averaging into an existing long position and forbids short selling. Sells are full-position only.

## D-087

A virtual fill updates order status, execution, cash ledger, and position in one atomic Room transaction.

## D-088

The paper trading engine never calls network or KIS trading APIs.

## D-089

Portfolio snapshots use exact trading-day closes. Missing prices fail the snapshot; prior-day close is not substituted.

## D-090

Order status includes `PENDING_EXECUTION` for next-day open fills that are waiting for market bars.

## D-091

Each strategy run has exactly one immutable paper trading policy snapshot (`UNIQUE strategy_run_id`).

## D-092

Running paper trading loads the run's policy snapshot. It does not read the live global code default.

## D-093

Commission and sell-tax rates in paper policy are SIMULATION ASSUMPTION values, not claimed legal or brokerage quotes.

## D-094

Changing paper trading policy means creating a new strategy run. Existing run snapshots are not updated.

## D-095

A READY/RUNNING run without a policy snapshot cannot trade (`MISSING_TRADING_POLICY`). Silent fallback is forbidden.

## D-096

`executed_at` stores the simulated market execution date as UTC midnight. It is not wall-clock fill time.

## D-097

`created_at` is the BJStock record creation timestamp. Analytics must not treat it as the trading day.

## D-098

Performance Analytics is read-only over Room history and never rewrites trading tables.

## D-099

`portfolio_daily_snapshots.total_asset` is the account-value source of truth for performance metrics.

## D-100

MDD uses only the peak observed up to each date (no look-ahead).

## D-101

A closed trade is one BUY→SELL round trip under Phase 6 position rules.

## D-102

Win rate excludes breakeven trades from the denominator.

## D-103

Holding period is calendar days between simulated market execution dates.

## D-104

CAGR is not shown when elapsed calendar days are under 365.

## D-105

Sharpe ratio and annualized volatility are out of scope for Phase 7.

## D-106

Benchmark index comparison is deferred to a later phase.

## D-107

Performance Analytics does not call network APIs.

## D-108

AI is advisory only and has no trade authority.

## D-109

AI must not mutate quant scores or quant/final decisions.

## D-110

AI must not create, cancel, or block paper trading orders.

## D-111

Phase 8 usable modes are OFF and CHATGPT_MANUAL. OPENAI_API_FUTURE is a placeholder only.

## D-112

OpenAI API secrets must never be stored in the Android APK.

## D-113

Future automated OpenAI access must go through a BJStock backend gateway, not a direct on-device secret.

## D-114

AI prompts must not include market data after the evaluation date.

## D-115

AI prompts are built from immutable evaluation snapshots, not live recalculated factors.

## D-116

AI prompts are versioned; V1 is never silently rewritten.

## D-117

AI results are append-only per request; revisions require a new request.

## D-118

AI confidence is not written to `stock_evaluations.ai_score`.

## D-119

Forward Test Universe is a per-run snapshot in `strategy_run_instruments` and is immutable after READY.

## D-120

WorkManager wake-up time is not market time; catch-up uses market dates, not worker wall clock.

## D-121

BJStock daily data cutoff is 18:00 Asia/Seoul as an operational assumption, not exchange law.

## D-122

Missing market dates are processed ASC (catch-up) from last COMPLETE exclusive through `throughDate` inclusive.

## D-123

A FAILED market date blocks processing of later dates until that date is retried successfully.

## D-124

Daily pipeline order is Pending Fill → Factor → Evaluation → Order → Snapshot.

## D-125

Pending fills with `asOfMarketDate` must not see bars after that date (future-bar poison blocked).

## D-126

On run end_date, new pending orders are not created from that day's signals.

## D-127

On run end_date, open positions are not force-liquidated; final snapshot marks to market at close.

## D-128

Forward Test auto scheduler default is OFF until physical-device acceptance.

## D-129

AI Advisory does not participate in the automatic forward-test pipeline and never blocks cycles.

## D-130

Existing instruments remains the KOSPI/KOSDAQ local master; no duplicate master table.

## D-131

Themes are many-to-many interest baskets over instruments.

## D-132

Theme membership changes do not mutate existing Forward Test universes.

## D-133

Strategy Run Universe is an immutable snapshot copied from Theme (or individual adds) while DRAFT.

## D-134

DAILY_CHANGE_PCT hard signal rules support BUY/SELL with GTE/LTE.

## D-135

Triggered signal rules take priority over factor-strategy decisions.

## D-136

When no signal rule triggers, evaluation falls back to the existing factor strategy.

## D-137

Signal rules are immutable on ACTIVE/RETIRED strategy versions (DRAFT-only mutation).

## D-138

Trade audit logs are permanently retained (append-only).

## D-139

Orders and executions remain the transaction source of truth; audit is a human-readable timeline.

## D-140

API error logs are separate from trade audit.

## D-141

API error logs use rolling 7-day retention.

## D-142

API error logs never store secrets, tokens, or raw HTTP bodies.

## D-143

`docs/150_OPERATIONAL_RELIABILITY_STANDARD.md` is the canonical reliability, error-handling, and operational-evidence standard. Paper trading does not use weaker controls because money is virtual.

## D-144

Canonical errors use `AppErrorCode` with category, severity, retry policy, user-action flag, operation action, audit flag, and a fixed safe message. Unknown errors map to `UNEXPECTED_EXCEPTION`. Free-text messages are never business logic.

## D-145

Every Forward Test invocation will own one `forward_operations` row with a deterministic unique `operation_key` (`worker:<work_id>:<attempt>` / `manual:<request_id>`).

Superseded in part by D-151: the Worker key is `worker:<work_id>:<through_date>:<attempt>`.

## D-146

Operational evidence lives in append-only `operational_events`, idempotent by deterministic `event_key`, separate from `trade_audit_logs` (WHY) and `api_error_logs` (WHAT failed externally).

## D-147

`operation_id` on `trade_audit_logs` and `api_error_logs` is a nullable soft reference without FK; `operational_events.operation_id` is a RESTRICT FK. Historical rows are never rewritten.

## D-148

Operational logging accepts only allowlisted typed fields. The existing deny-list sanitizer remains as defence-in-depth.

## D-149

The retention matrix in `docs/150` is the target lifecycle. Until verified archive and lifecycle jobs exist, D-138 (permanent trade audit) and D-141 (7-day API error cleanup) remain the runtime behavior.

## D-150

Phase 11 foundation (Room v8) does not change Auto Forward Test, Run Now, or `ForwardTestWorker` behavior. Wiring happens in later gates.

## D-151

Phase 11 / Gate 5: the Worker operation key is the interim `worker:<work_id>:<through_date>:<attempt>`, because a `PeriodicWorkRequest` reuses its work id and resets its run attempt every period. Redelivery of the same work id, through-date, and attempt resolves to the same row without re-executing. The future canonical key `worker:<schedule_instance_id>:<attempt>` (e.g. `auto:2026-10-01:0730:KST`) arrives with the scheduler gate. Manual keys stay `manual:<request_id>`.

## D-152

All state-mutating Forward Test entry points (Run Now, `ForwardTestWorker`, Retry Failed Cycle) execute only through `ForwardTestExecutionCoordinator`, which owns the operation row, the operation-level through-date, the aggregate result, and a process-wide non-waiting single-flight guard. An overlapping invocation is persisted as `BLOCKED` / `ALREADY_RUNNING` and does not execute; the Worker returns retry for it.

## D-153

`forward_operations.operation_kind` (`FORWARD_RUN` / `RETRY_FAILED_CYCLE`, Room v9 / PostgreSQL `0011`) is separate from `trigger` (`MANUAL` / `WORKER`) and never inferred from the key. Existing rows are `FORWARD_RUN`. Retry uses `manual-retry:<request_id>`, is validated against its target cycle before any mutation, and never mutates a missing, non-FAILED, or other-run target.

## D-154

Run isolation is unchanged: a non-retryable block still stops later runs. Every selected run gets exactly one `RUN_RESULT`; later runs are `SKIPPED` / `PRIOR_RUN_BLOCKED`, never `FAILED` / `BLOCKED`. Operation status is `PARTIAL` when a block follows meaningful progress and `BLOCKED` when the first run blocks. The Worker result derives from the aggregate (non-retryable → failure, retryable → retry, otherwise success).

## D-155

Phase 11 / Gate 6: every required trade audit row commits in the same Room transaction as its business mutation (evaluation, order creation, skip, rejection, cancellation, fill). `ORDER_REJECTED` (`INSUFFICIENT_CASH` / `NO_POSITION_TO_SELL`) and `ORDER_CANCELLED` (`RUN_END_REACHED`) are now emitted. Missing legacy `EVALUATION_DECIDED` / `ORDER_CREATED` / `EXECUTION_FILLED` rows are restored on replay by deterministic key with `reason_code = LEGACY_AUDIT_RESTORED` and `operation_id = NULL`; business rows are never modified and unprovable details are never fabricated.

## D-156

`executions.execution_key` and `cash_ledger.event_key` are NOT NULL and unique (Room v10 / PostgreSQL `0012`). Paper fills use `paper:order:<order_id>:fill:1`; `UNIQUE (order_id)` is deliberately not used so future broker partial fills can use `:fill:<n>`. Ledger keys: `run:<run>:initial-deposit`, `execution:<id>:buy-principal|buy-commission|sell-proceeds|sell-commission|sell-tax`. The legacy migration derives keys only from provable identity and aborts on ambiguity.

## D-157

A duplicate canonical key is an idempotent replay only when the stored row is the same logical event. Otherwise the write aborts with `IntegrityViolationException`: execution / ledger conflicts map to `DATA_INTEGRITY_ERROR` with severity `FINANCIAL_INTEGRITY`; audit key conflicts map to `INTERNAL_INVARIANT_VIOLATION`. The planned `LEDGER_MISMATCH` / `DUPLICATE_EXECUTION` codes (`docs/150` 4.2) are not added in Gate 6.

## D-158

The paper execution transaction is: order terminal transition + execution + cash ledger group + position + `EXECUTION_FILLED`. The daily portfolio snapshot stays outside it and is recomputed from committed state.

## D-159

Phase 11 / Gate 6.1: `LEDGER_MISMATCH` and `EXECUTION_IDEMPOTENCY_CONFLICT` are catalog codes (`INVARIANT`, `FINANCIAL_INTEGRITY`, retry `NONE`, user action required, `ABORT_OPERATION`, audit required). They replace `DATA_INTEGRITY_ERROR` for ledger / execution conflicts; `IntegrityViolationException` severity always equals its code's catalog severity, so `FINANCIAL_INTEGRITY` is never collapsed to `CRITICAL`. `DUPLICATE_EXECUTION` is not added: an exact replay with identical facts is idempotent success. A ledger append also aborts with `LEDGER_MISMATCH` when the latest balance differs from the sum of amounts. Supersedes the code choice in D-157.

## D-160

Run-end finalization is one transaction: every pending order → `CANCELLED` with its `ORDER_CANCELLED` (`RUN_END_REACHED`), then the run → `COMPLETED`. All or nothing. Qualifying orders are unchanged.

## D-161

Legacy `REJECTED` / `CANCELLED` orders without audit get exactly one reconciliation audit via a data-only Room `MIGRATION_10_11` / PostgreSQL `0013` (append-only, idempotent, orders untouched, `operation_id` NULL). The reason is used only when the pre-Gate-6 writer's persisted signature proves it (repository history: REJECTED `quantity = 0` per side, CANCELLED with `cancelled_at`); otherwise `LEGACY_REASON_UNKNOWN`. Historical market / account state is never recalculated.

## D-162

`decision_source` stays `SIGNAL_RULE` / `FACTOR_STRATEGY`; `LEGACY_UNKNOWN` is not added and the PostgreSQL CHECK is unchanged (human decision). A restored `EVALUATION_DECIDED` uses the proven source (`RULE_TRIGGERED` present → `SIGNAL_RULE`; version without enabled signal rules → `FACTOR_STRATEGY`) or NULL with `reason_code = LEGACY_AUDIT_RESTORED`. Live rows never use NULL. Existing audit rows, including restored NULL-source rows, are never rewritten.

