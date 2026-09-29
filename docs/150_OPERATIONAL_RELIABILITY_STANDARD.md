# Operational Reliability Standard

Canonical reliability, error-handling, and operational-evidence standard for BJStock.

## 0. Scope and Status

This standard is written to be **portable later to CatchMenu and other projects**. Sections 1–19 are project-neutral in intent. Every implementation contract in this document (table names, Room versions, codes, services) is **BJStock-specific**.

| Item | Status |
| --- | --- |
| Standard (this document) | Canonical from Phase 11 |
| Canonical error model (`core/error`) | Implemented — foundation |
| `forward_operations` / `operational_events` schema | Implemented — Room v8 / PostgreSQL `0009`; `operation_kind` Room v9 / `0011` |
| `operation_id` on `trade_audit_logs` / `api_error_logs` | Implemented — populated for rows written inside an operation (Gate 5) |
| `ForwardOperationLogService` | Implemented — wired through `ForwardTestExecutionCoordinator` for Run Now, Worker, Retry Failed Cycle (Gate 5, 20.4) |
| Single-flight between Forward Test entry points | Implemented — Gate 5 (20.4) |
| Audit atomicity (evaluation, order create / skip / reject / cancel, fill) | Implemented — Gate 6 (20.5) |
| Canonical `executions.execution_key` / `cash_ledger.event_key` | Implemented — Room v10 / PostgreSQL `0012` (Gate 6, 20.5) |
| `LEDGER_MISMATCH` / `EXECUTION_IDEMPOTENCY_CONFLICT` (`FINANCIAL_INTEGRITY`) | Implemented — Gate 6.1 (20.6) |
| `FILLED_ORDER_WITHOUT_EXECUTION` (`FINANCIAL_INTEGRITY`) | Implemented — Gate 6.2 (20.7) |
| Atomic run-end finalization (cancellations + audits + run `COMPLETED`) | Implemented — Gate 6.1 (20.6) |
| Legacy `REJECTED` / `CANCELLED` order audit reconciliation | Implemented — Room v11 / PostgreSQL `0013`, data-only (Gate 6.1, 20.6) |
| Interrupted (orphan `RUNNING`) operation recovery on app start | Implemented — Gate 7A (20.8) |
| Scheduler rework, retention/archive, Operations UI | **Not implemented** — later Phase 11 gates |

Auto Forward Test scheduling (periodic work, 18:00 Asia/Seoul cutoff), trading math, 7-day API error cleanup, and permanent trade audit are unchanged by Gates 5, 6, 6.1, 6.2, and 7A.

---

## 1. Purpose

Paper trading must **not** use weaker reliability controls merely because money is virtual. BJStock is an operational rehearsal for future real-money trading.

Primary requirements:

- every important operation is traceable
- every known error is classified
- every unexpected error is preserved safely
- no silent catch
- no secret leakage
- retry behavior is explicit
- domain / audit evidence is durable
- financial / source-of-truth records are never treated as disposable logs
- incident → root cause → regression test lifecycle

---

## 2. Error Taxonomy

Canonical categories (`ErrorCategory`):

| Category | Meaning | Examples |
| --- | --- | --- |
| `DOMAIN` | A business rule decided the outcome. Not a system fault. | `NO_POSITION_TO_SELL`, `INSUFFICIENT_CASH`, `INSUFFICIENT_WARMUP_DATA`, `INVALID_RUN_STATE` |
| `EXTERNAL` | An external provider answered, but not successfully. | `KIS_SERVER_ERROR`, `KIS_BUSINESS_ERROR` |
| `TRANSIENT` | Temporary infrastructure condition; expected to clear. | `NETWORK_UNAVAILABLE`, `NETWORK_TIMEOUT`, `KIS_RATE_LIMIT` |
| `SECURITY` | Credentials / authentication state prevents work. | `CREDENTIAL_MISSING`, `CREDENTIAL_REJECTED`, `AUTH_REQUIRED` |
| `INVARIANT` | Internal consistency guarantee violated. | `EXECUTION_IDEMPOTENCY_CONFLICT`, `LEDGER_MISMATCH`, `INVALID_STATE_TRANSITION`, `DB_CONSTRAINT_VIOLATION` |
| `UNEXPECTED` | Not yet understood. | `UNEXPECTED_EXCEPTION` |

Unknown errors **must** initially map to `UNEXPECTED_EXCEPTION`. They are never guessed into a friendlier category.

During alpha / beta:

```text
UNKNOWN ERROR
 -> evidence capture
 -> root cause
 -> new canonical error code if appropriate
 -> handling policy
 -> regression test
 -> KNOWN ERROR
```

---

## 3. Error Severity

`ErrorSeverity`, ascending:

| Severity | Meaning |
| --- | --- |
| `INFO` | Expected domain outcome worth recording. |
| `WARNING` | Undesired but safe; no degradation. |
| `DEGRADED` | Service continues with reduced capability (e.g. rate limit, transient network). |
| `ERROR` | A unit of work (e.g. one Run) failed. |
| `CRITICAL` | Integrity risk (e.g. duplicate-order / invariant risk). |
| `FINANCIAL_INTEGRITY` | **Highest.** Cash ledger / execution / position disagreement. |

Examples:

| Situation | Severity |
| --- | --- |
| KIS rate limit | `DEGRADED` |
| single Run failure | `ERROR` |
| duplicate-order / invariant risk | `CRITICAL` |
| cash ledger / execution mismatch | `FINANCIAL_INTEGRITY` |

---

## 4. Error Catalog Contract

Every canonical error code defines:

| Attribute | Meaning |
| --- | --- |
| `code` | Stable UPPER_SNAKE identifier. Persisted as text; never an ordinal. |
| `category` | Section 2 |
| `severity` | Section 3 |
| `retry_policy` | Section 5 |
| `user_action_required` | A human must act before the work can succeed. |
| `safe_message` | Fixed, secret-free, user-presentable text. |
| `operation_action` | What the orchestrator does (below). |
| `audit_required` | A domain `trade_audit_logs` event must accompany this outcome. |

`operation_action` vocabulary (`OperationAction`):

| Action | Meaning |
| --- | --- |
| `CONTINUE` | Record and keep processing the current unit. |
| `SKIP` | Skip this item (e.g. one order); continue the Run. |
| `BLOCK_RUN` | Stop this Run for this operation; other Runs continue; a later operation may retry automatically. |
| `RETRY_OPERATION` | Stop this Run for this operation and request an operation-level retry. |
| `ABORT_OPERATION` | Stop the whole operation. Used for invariant / integrity risk. |
| `REQUIRE_USER_ACTION` | Block this Run until a human acts; automatic retries must not clear it. |

Arbitrary free-text error strings **must not** be used as business logic. Branching uses `AppErrorCode` (or existing typed enums mapped to it), never message text.

### 4.1 BJStock implemented catalog (`AppErrorCode`)

Only codes needed by existing Forward Test / KIS / paper-trading paths, plus `UNEXPECTED_EXCEPTION` and `INTERNAL_INVARIANT_VIOLATION`.

| Code | Category | Severity | Retry | User action | Operation action | Audit |
| --- | --- | --- | --- | --- | --- | --- |
| `NO_POSITION_TO_SELL` | DOMAIN | INFO | NONE | no | SKIP | yes |
| `POSITION_ALREADY_OPEN` | DOMAIN | INFO | NONE | no | SKIP | yes |
| `INSUFFICIENT_CASH` | DOMAIN | WARNING | NONE | no | SKIP | yes |
| `INSUFFICIENT_WARMUP_DATA` | DOMAIN | WARNING | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `INVALID_RUN_STATE` | DOMAIN | ERROR | NONE | no | SKIP | no |
| `EMPTY_UNIVERSE` | DOMAIN | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `MISSING_TRADING_POLICY` | DOMAIN | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `SNAPSHOT_MISSING_PRICE` | DOMAIN | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `CYCLE_FAILED` | DOMAIN | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `KIS_SERVER_ERROR` | EXTERNAL | DEGRADED | EXPONENTIAL_BACKOFF | no | RETRY_OPERATION | no |
| `KIS_BUSINESS_ERROR` | EXTERNAL | ERROR | NONE | no | BLOCK_RUN | no |
| `KIS_MALFORMED_RESPONSE` | EXTERNAL | ERROR | NONE | no | BLOCK_RUN | no |
| `NETWORK_UNAVAILABLE` | TRANSIENT | DEGRADED | EXPONENTIAL_BACKOFF | no | RETRY_OPERATION | no |
| `NETWORK_TIMEOUT` | TRANSIENT | DEGRADED | EXPONENTIAL_BACKOFF | no | RETRY_OPERATION | no |
| `KIS_RATE_LIMIT` | TRANSIENT | DEGRADED | FIXED_DELAY | no | RETRY_OPERATION | no |
| `CREDENTIAL_MISSING` | SECURITY | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `CREDENTIAL_REJECTED` | SECURITY | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `AUTH_REQUIRED` | SECURITY | ERROR | USER_ACTION_REQUIRED | yes | REQUIRE_USER_ACTION | no |
| `DATA_INTEGRITY_ERROR` | INVARIANT | CRITICAL | NONE | yes | ABORT_OPERATION | no |
| `LEDGER_MISMATCH` | INVARIANT | FINANCIAL_INTEGRITY | NONE | yes | ABORT_OPERATION | yes |
| `EXECUTION_IDEMPOTENCY_CONFLICT` | INVARIANT | FINANCIAL_INTEGRITY | NONE | yes | ABORT_OPERATION | yes |
| `FILLED_ORDER_WITHOUT_EXECUTION` | INVARIANT | FINANCIAL_INTEGRITY | NONE | yes | ABORT_OPERATION | yes |
| `INTERNAL_INVARIANT_VIOLATION` | INVARIANT | CRITICAL | NONE | yes | ABORT_OPERATION | no |
| `UNEXPECTED_EXCEPTION` | UNEXPECTED | ERROR | NONE | no | ABORT_OPERATION | no |

Safe messages are defined once in `AppErrorCode` and are the only text persisted for a code.

### 4.2 Planned catalog (not yet in code)

Reserved for the idempotency / audit-atomicity gates. Added to `AppErrorCode` only when a real path emits them, with a regression test.

| Code | Category | Severity | Retry | Operation action |
| --- | --- | --- | --- | --- |
| `INVALID_STATE_TRANSITION` | INVARIANT | CRITICAL | NONE | ABORT_OPERATION |
| `DB_CONSTRAINT_VIOLATION` | INVARIANT | CRITICAL | NONE | ABORT_OPERATION |

Gate 6.1 moved `LEDGER_MISMATCH` into 4.1 and added `EXECUTION_IDEMPOTENCY_CONFLICT` there. `DUPLICATE_EXECUTION` is **not** added: an exact replay of the same `execution_key` with identical financial facts is normal idempotent success, not an error (20.6.1). Gate 6.2 added `FILLED_ORDER_WITHOUT_EXECUTION` (20.7).

`IntegrityViolationException` carries only a code; its severity is always the catalog severity of that code, so `SafeAppError.severity` reports `FINANCIAL_INTEGRITY` for the three financial codes and `CRITICAL` for audit-key conflicts (`INTERNAL_INVARIANT_VIOLATION`).

### 4.3 Mapping from existing BJStock codes

| Existing | Canonical |
| --- | --- |
| `ForwardErrorCode.INVALID_RUN` | `INVALID_RUN_STATE` |
| `ForwardErrorCode.NETWORK_FAILURE` | `NETWORK_UNAVAILABLE` |
| other `ForwardErrorCode.*` | same name |
| `KisMarketErrorKind.AUTHENTICATION` | `AUTH_REQUIRED` |
| `KisMarketErrorKind.HTTP` | `KIS_SERVER_ERROR` |
| `KisMarketErrorKind.BUSINESS` (provider `rt_cd != 0`, not rate limit) | `KIS_BUSINESS_ERROR` |
| `KisMarketErrorKind.INVALID_SYMBOL` / `INVALID_DATE_RANGE` (local validation before the request) | `INTERNAL_INVARIANT_VIOLATION` |
| `KisMarketErrorKind.RATE_LIMITED` | `KIS_RATE_LIMIT` |
| `KisMarketErrorKind.NETWORK_TIMEOUT` | `NETWORK_TIMEOUT` |
| `KisMarketErrorKind.MALFORMED_RESPONSE` / `MAPPING_FAILURE` | `KIS_MALFORMED_RESPONSE` |
| `KisAuthException` HTTP 401 / 403 | `CREDENTIAL_REJECTED` |
| `KisAuthException` HTTP 5xx | `KIS_SERVER_ERROR` |
| `KisAuthException` other / no HTTP code | `AUTH_REQUIRED` (typed kind needed; see 20.3) |
| `HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND` | `DATA_INTEGRITY_ERROR` |
| `HistoricalSyncErrorKind.INVALID_DATE_RANGE` / `NO_LATEST_BAR` | `INTERNAL_INVARIANT_VIOLATION` |
| `IntegrityViolationException` | its `code` (`LEDGER_MISMATCH` / `EXECUTION_IDEMPOTENCY_CONFLICT` / `FILLED_ORDER_WITHOUT_EXECUTION` / `INTERNAL_INVARIANT_VIOLATION`), severity preserved |
| `java.net.SocketTimeoutException` | `NETWORK_TIMEOUT` |
| other `java.io.IOException` | `NETWORK_UNAVAILABLE` |
| anything else | `UNEXPECTED_EXCEPTION` |

`kotlinx.coroutines.CancellationException` is never converted into an error; it is rethrown.

---

## 5. Retry Policy

Centralized retry classes (`RetryPolicy`):

| Policy | Meaning |
| --- | --- |
| `NONE` | Never retried automatically. |
| `BOUNDED_IMMEDIATE` | Small fixed number of immediate re-attempts within the call. |
| `FIXED_DELAY` | Provider-specific bounded retry with a fixed wait. |
| `EXPONENTIAL_BACKOFF` | Operation-level retry with exponential backoff and an attempt cap. |
| `USER_ACTION_REQUIRED` | No automatic retry; a human must resolve the cause. |

Examples:

| Error | Policy |
| --- | --- |
| `NETWORK_TIMEOUT` | `EXPONENTIAL_BACKOFF` |
| `KIS_RATE_LIMIT` | `FIXED_DELAY` — provider-specific bounded retry (current `KisRequestPolicy`: 61 s wait, max 3 attempts per call) |
| `CREDENTIAL_REJECTED` | `USER_ACTION_REQUIRED` |
| INVARIANT failure | `NONE` + `ABORT_OPERATION` |

Rules:

- Individual functions must not invent their own retry rules. Retry behavior is derived from `AppErrorCode.retryPolicy`.
- Operation-level backoff parameters (base delay, attempt cap) are fixed in the scheduler gate and recorded here when approved.
- WorkManager `Result` is derived from the aggregate operation outcome; it is not the domain outcome.
- A later successful Run in the same operation must not erase an earlier Run's retry request.

---

## 6. Error Boundaries

| Layer | Responsibility |
| --- | --- |
| Infrastructure / Repository | Translate technical exceptions (HTTP, IO, SQLite, serialization) into canonical application errors (`SafeAppError`). |
| Domain / UseCase | Decide domain meaning and policy (skip, reject, block). |
| Orchestrator | Decide skip / block / retry / abort per `operation_action`; isolate Runs. |
| Worker / UI boundary | Complete the operation result and present the safe user-facing message. |

Prohibited:

```kotlin
catch (e: Exception) { return false }
catch (e: Exception) { /* ignore */ }
runCatching { ... }            // result discarded
catch (e: Exception) { log(e.message) }  // raw message persisted
```

Rules:

- No silent swallowing. A catch must either translate into a canonical error, record evidence, or rethrow.
- `CancellationException` is always rethrown.
- Unexpected exceptions preserve safe diagnostic context (exception simple class name, logical endpoint, HTTP status, business code, attempt) and propagate to the responsible boundary.
- Raw `Throwable.message` is never persisted; it may contain request data.

---

## 7. Operation Correlation

Every Forward Test invocation gets one durable `forward_operations` row.

`trigger` (who started it): `MANUAL` | `WORKER`

`operation_kind` (what it does): `FORWARD_RUN` | `RETRY_FAILED_CYCLE`

Trigger and kind are independent columns. The kind is never inferred from the key.

| Entry point | `trigger` | `operation_kind` | `operation_key` |
| --- | --- | --- | --- |
| Run Now | `MANUAL` | `FORWARD_RUN` | `manual:<request_id>` |
| `ForwardTestWorker` | `WORKER` | `FORWARD_RUN` | `worker:<work_id>:<through_date>:<attempt>` (interim, 20.4.3) |
| Retry Failed Cycle | `MANUAL` | `RETRY_FAILED_CYCLE` | `manual-retry:<request_id>` |

Correlation chain:

```text
operation_id
 -> strategy_run
 -> cycle
 -> evaluation
 -> order
 -> execution
```

Links:

- operation → run / cycle: `operational_events.run_id` / `cycle_id` (`RUN_RESULT`, `CYCLE_STARTED`, `CYCLE_FINISHED`)
- operation → evaluation / order / execution: `trade_audit_logs.operation_id` (audit rows already carry evaluation / order / execution ids)
- operation → external failure: `api_error_logs.operation_id`

### 7.1 `forward_operations` (one row per invocation)

| Field | Type (Room) | Notes |
| --- | --- | --- |
| `id` | INTEGER PK | autoincrement |
| `operation_key` | TEXT NOT NULL UNIQUE | deterministic: `worker:<work_id>:<through_date>:<attempt>`, `manual:<request_id>`, or `manual-retry:<request_id>` |
| `trigger` | TEXT NOT NULL | `MANUAL` / `WORKER` |
| `operation_kind` | TEXT NOT NULL DEFAULT `'FORWARD_RUN'` | `FORWARD_RUN` / `RETRY_FAILED_CYCLE`; rows created before Room v9 / `0011` are `FORWARD_RUN` |
| `work_id` | TEXT nullable | WorkManager request id; required for WORKER, null for MANUAL |
| `work_attempt` | INTEGER nullable | WorkManager run attempt; required for WORKER, null for MANUAL |
| `through_date` | INTEGER NOT NULL | epoch day; operation-level nominal market through-date |
| `status` | TEXT NOT NULL | vocabulary below |
| `started_at` | INTEGER NOT NULL | epoch millis UTC |
| `finished_at` | INTEGER nullable | epoch millis UTC; null only while RUNNING |
| `final_code` | TEXT nullable | canonical code token |
| `safe_message` | TEXT nullable | allowlist-validated |
| `runs_considered` | INTEGER NOT NULL | |
| `runs_processed` | INTEGER NOT NULL | |
| `runs_skipped` | INTEGER NOT NULL | |
| `cycles_completed` | INTEGER NOT NULL | |
| `cycles_failed` | INTEGER NOT NULL | |
| `elapsed_ms` | INTEGER nullable | `finished_at - started_at`, never negative |

Status vocabulary (`ForwardOperationStatus`): `RUNNING`, `SUCCEEDED`, `NO_OP`, `PARTIAL`, `BLOCKED`, `FAILED`.

Rules:

- A row is created `RUNNING` and transitions exactly once to a terminal status. Terminal rows are immutable.
- Replaying `startOperation` with the same `operation_key` returns the existing row; it never creates a second row.
- Replaying `finishOperation` on a terminal row returns `AlreadyFinished`; it never overwrites.

---

## 8. Operational Events

Operational events are separate from `trade_audit_logs`.

Minimum taxonomy (`OperationalEventType`):

| Event | Emitted by | Deterministic key |
| --- | --- | --- |
| `OPERATION_STARTED` | `startOperation` (same transaction) | `op:<operation_id>:started` |
| `OPERATION_FINISHED` | `finishOperation` (same transaction) | `op:<operation_id>:finished` |
| `MARKET_SYNC_RESULT` | coordinator, per attempted run sync | `op:<operation_id>:run:<run_id>:sync` |
| `RUN_RESULT` | coordinator, per selected run | `op:<operation_id>:run:<run_id>:result` |
| `CYCLE_STARTED` | coordinator, per cycle attempt | `op:<operation_id>:cycle:<cycle_id>:attempt:<n>:started` |
| `CYCLE_FINISHED` | coordinator, per cycle attempt | `op:<operation_id>:cycle:<cycle_id>:attempt:<n>:finished` |
| `WORKER_SCHEDULE_CHANGED` | scheduler (not yet emitted; scheduler gate) | `schedule:<action>:<epoch_millis>` |

The orchestrator reports through `ForwardExecutionObserver` hooks; the coordinator's recorder persists the events (20.4.4).

No per-factor / per-row spam.

Each event:

| Field | Notes |
| --- | --- |
| `event_key` | UNIQUE; deterministic; restricted charset |
| `operation_id` | FK → `forward_operations.id` RESTRICT. Nullable **only** for `WORKER_SCHEDULE_CHANGED` (schedule changes happen outside an operation). |
| `run_id` | nullable soft reference |
| `cycle_id` | nullable soft reference |
| `instrument_id` | nullable soft reference |
| `market_date` | nullable epoch day |
| `event_type` | vocabulary above |
| `result` | nullable canonical token |
| `reason_code` | nullable canonical token |
| `safe_message` | nullable allowlist-validated |
| `elapsed_ms` | nullable, non-negative |
| `created_at` | epoch millis UTC |

Semantics:

- Append-only. The DAO exposes no update or delete.
- Insert is idempotent by `event_key`. Replay returns the existing row and never duplicates or overwrites it.
- `OPERATION_STARTED` / `OPERATION_FINISHED` can only be written through `startOperation` / `finishOperation`.
- No arbitrary payload field (no JSON, headers, bodies).

---

## 9. Three Log Layers

| Layer | Tables | Answers |
| --- | --- | --- |
| 1. DOMAIN AUDIT | `trade_audit_logs` | **WHY** did the trading action happen? |
| 2. OPERATIONAL EVIDENCE | `forward_operations`, `operational_events` | **HOW** did the system execute? |
| 3. API / EXTERNAL ERROR | `api_error_logs` | **WHAT** failed at the external boundary? |

Do not mix these responsibilities. Lifecycle events are never written to `trade_audit_logs`; trading reasons are never written to `operational_events`. The layers share only correlation ids.

---

## 10. Source-of-Truth Boundary

These are **not** disposable logs:

- `orders`
- `executions`
- `cash_ledger`
- `positions`
- `portfolio_daily_snapshots`
- `stock_evaluations` / `stock_evaluation_details` as canonical business evidence

They are financial / domain state. Log cleanup, rotation, archive, and purge **must never** touch them. Any retention for them is a separate, explicitly approved policy.

---

## 11. Audit Atomicity

Gate 1 finding: business state and its required audit event were written separately. **Resolved in Phase 11 / Gate 6 for the flows below** (details in 20.5).

| Flow | Before Gate 6 | After Gate 6 |
| --- | --- | --- |
| evaluation | `persistSnapshot` commits, then `writeAudit` | evaluation + details + `RULE_TRIGGERED` + `EVALUATION_DECIDED` in one transaction |
| order creation | order insert, then `ORDER_CREATED` | order + `ORDER_CREATED` in one transaction |
| order skip | skip audit written after position read | position read + `ORDER_SKIPPED` in one transaction; no order row |
| order rejection | status → REJECTED; **no** `ORDER_REJECTED` emitted | REJECTED + `ORDER_REJECTED` in one transaction |
| order cancellation | status → CANCELLED at run end; **no** `ORDER_CANCELLED` emitted | every run-end CANCELLED + `ORDER_CANCELLED` + run `COMPLETED` in one transaction (Gate 6.1; Gate 6 had one transaction per order) |
| execution fill | fill transaction commits, then `EXECUTION_FILLED` appended outside it | `EXECUTION_FILLED` inside the fill transaction |

Contract:

- Where feasible, business mutation and required audit write commit in the **same Room transaction**. At minimum: evaluation, order creation, order rejection, order cancellation, execution fill.
- If the same transaction is impossible, rerun / reconciliation must restore the missing audit using the deterministic `event_key`.
- Evaluation audit details (decision source, rule, observed / threshold values) are not stored on `stock_evaluations`; they cannot be faithfully reconstructed later. Evaluation audit therefore **requires** same-transaction writes.

---

## 12. Idempotency

Current protections (preserved):

| Table | Unique |
| --- | --- |
| `market_daily_bars` | `(instrument_id, trade_date)` |
| `factor_values` | `(instrument_id, factor_id, evaluation_date, calculation_version)` |
| `stock_evaluations` | `(strategy_run_id, instrument_id, evaluation_date)` |
| `stock_evaluation_details` | `(evaluation_id, factor_id)` |
| `orders` | `client_order_id` |
| `positions` | `(strategy_run_id, instrument_id)` |
| `portfolio_daily_snapshots` | `(strategy_run_id, snapshot_date)` |
| `forward_test_cycles` | `(strategy_run_id, market_date)` |
| `paper_trading_policies` | `strategy_run_id` |
| `trade_audit_logs` | `event_key` |
| `forward_operations` | `operation_key` |
| `operational_events` | `event_key` |
| `executions` | `execution_key` (Gate 6) |
| `cash_ledger` | `event_key` (Gate 6) |

Previous gaps, **resolved in Phase 11 / Gate 6** (20.5):

- `executions`: no DB unique guarantee per canonical paper order → `execution_key` `paper:order:<order_id>:fill:1`.
- `cash_ledger`: no canonical unique financial-event key → `event_key`.

Rules:

- Do **not** yet force a constraint that would prevent future partial fills (e.g. plain `UNIQUE(order_id)` on executions).
- Before real-money readiness, every execution and every cash-ledger movement must carry a canonical logical event key (e.g. `(order_id, fill_seq)` for executions; `(reference_type, reference_id, event_type)` or an explicit ledger event key for cash), enforced by a unique index, with a migration that fails loudly on existing duplicates.

---

## 13. Log Lifecycle

```text
HOT -> ROTATE -> COMPRESS -> ENCRYPT (if required) -> COLD ARCHIVE -> PURGE
```

Every log class declares: Hot Retention, Rotation, Archive Retention, Compression, Encryption, Integrity, Final Purge, Surviving Summary (Section 14).

---

## 14. Retention Matrix

Canonical initial policy (**target**; lifecycle jobs are not implemented yet — see 20.2):

| Class | Store | Hot | Rotation | Archive retention | Compression | Encryption | Integrity | Final purge | Surviving summary |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DOMAIN AUDIT | `trade_audit_logs` | 400 days | monthly | 5 years | yes | yes | manifest SHA-256 + count | after 5 years, only after verified archive | manifest / aggregate |
| OPERATION SUMMARY | `forward_operations` | 400 days | monthly | 5 years | yes | yes | manifest SHA-256 + count | after verified 5-year retention | manifest / aggregate |
| OPERATION DETAIL | `operational_events` | 90 days | monthly | 400 days | yes | yes | manifest SHA-256 + count | after 400 days | operation summary survives longer |
| API ERROR | `api_error_logs` | 90 days | daily age check | none (raw) | – | – | – | rolling | daily aggregate 400 days |
| SECURITY / AUTH metadata | sanitized only | 90 days | monthly | 400 days | yes | yes | manifest SHA-256 + count | after 400 days | manifest |
| DEBUG / TRACE | Logcat / debug file | 3 days | daily / size | none | – | – | – | rolling | none |
| RAW NETWORK DIAGNOSTIC | disabled by default; diagnostic mode only | max 7 days | daily | none | – | – | – | rolling | none |
| DAILY / MONTHLY SUMMARY | summary store | long-term | – | – | – | – | – | – | itself; no secret / raw sensitive payload |

Source-of-truth tables (Section 10) are outside this matrix.

---

## 15. Archive Contract

Preferred future archive:

```text
JSONL -> gzip -> AES-GCM
*.jsonl.gz.enc
```

- Compression is **not** encryption.
- Compiled / opaque binary formats are **not** considered security.
- Future encryption: Android Keystore + AES-GCM (unique IV per artifact; no hard-coded key; no key in logs; failure never falls back to plaintext).
- Secrets must still never be logged; encryption is not a license to store them.

---

## 16. Archive Manifest

Future archive manifest fields:

`archive_id`, `schema_version`, `log_class`, `period_start`, `period_end`, `record_count`, `first_timestamp`, `last_timestamp`, source watermark, `compression`, `encryption`, `created_at`, SHA-256.

Crash-safe rule: **never purge hot rows before archive verification succeeds.**

---

## 17. Redaction

Primary policy: **ALLOWLIST.** The new operational logging API accepts only typed, allowlisted fields.

Allowed examples:

`operation_id`, `run_id`, `cycle_id`, `instrument_id`, market date, logical endpoint, HTTP status, business code, canonical reason code, safe message, attempt, `order_id`, `execution_id`, `elapsed_ms`, counts.

Never persist:

- KIS app secret (and app key)
- access token, refresh token
- Authorization header
- credentials
- account number
- raw headers
- raw request body
- raw sensitive response body
- device IDs unnecessarily

BJStock implementation (`SafeLogText`):

- Codes / result tokens: `^[A-Z][A-Z0-9_]{0,63}$`; anything else is rejected.
- Event / operation keys: restricted charset and length.
- Safe messages: whitespace normalized; max 200 chars; allowed characters are letters, digits, space, and `. , : ; ( ) - _ / % + # ' ! ? [ ]`. Messages containing any other character (e.g. `= " { } < > & @ \`), a secret-like keyword, an opaque token-like run (≥ 32 base64/hex-like chars, JWT prefix), or an account-number-like digit pattern are replaced by the fixed text `details withheld`.
- The existing `ApiErrorLogService.sanitize` deny-list is kept and also applied as defence-in-depth.
- `SafeAppError` diagnostics carry only exception simple class name, HTTP status, business code, logical endpoint, and attempt — never `Throwable.message`.

---

## 18. Incident Lifecycle

Every meaningful alpha / beta incident follows:

```text
Observed Error
 -> Canonical Error Code
 -> Evidence
 -> Root Cause
 -> Impact Scope
 -> Minimal Fix
 -> Regression Test
 -> Resolved
```

A bug is **not** closed merely because the developer can no longer reproduce it manually. A regression test must protect the fix where technically feasible.

---

## 19. Real-Money Readiness

Future order / payment style state machines must preserve stages so a failure can be located precisely:

```text
ORDER_INTENT_CREATED
BROKER_REQUEST_STARTED
BROKER_ACCEPTED
LOCAL_PERSISTED
EXECUTION_RECEIVED
LEDGER_UPDATED
RECONCILED
```

This phase does **not** implement broker trading (D-002, D-036, D-038 remain). This section defines future diagnostic requirements only.

---

## 20. BJStock Implementation Contract

### 20.1 Foundation delivered (Phase 11)

| Component | Location |
| --- | --- |
| `ErrorCategory`, `ErrorSeverity`, `RetryPolicy`, `OperationAction` | `core/error/ErrorModel.kt` |
| `AppErrorCode` (catalog 4.1) | `core/error/AppErrorCode.kt` |
| `SafeAppError`, `SafeDiagnostics`, `AppErrorMapper` | `core/error/SafeAppError.kt`, `core/error/AppErrorMapper.kt` |
| `SafeLogText` (allowlist redaction) | `core/error/SafeLogText.kt` |
| `ForwardOperationTrigger`, `ForwardOperationKind`, `ForwardOperationStatus`, `OperationalEventType`, `ForwardRunResult`, `ForwardOutcomeReason` | `core/model/DomainCodes.kt` |
| `ForwardOperationEntity` / `OperationalEventEntity` + DAOs | `core/database` |
| `ForwardOperationLogService` (`startOperation`, `finishOperation`, `appendOperationalEvent`) | `core/audit/ForwardOperationLogService.kt` |
| `ForwardOperationContext` (operation id as a coroutine-context element) | `core/audit/ForwardOperationContext.kt` |
| `ForwardTestExecutionCoordinator` (Gate 5) | `core/forward/ForwardTestExecutionCoordinator.kt` |
| Room v8 + `MIGRATION_7_8`; Room v9 + `MIGRATION_8_9` (`operation_kind`); Room v10 + `MIGRATION_9_10` (financial event keys); Room v11 + `MIGRATION_10_11` (data-only terminal-order audit reconciliation) | `core/database/BJStockMigrations.kt` |
| PostgreSQL parity | `db/migrations/0009_operational_reliability_foundation.sql`, `0010_api_error_type_taxonomy.sql`, `0011_forward_operation_kind.sql`, `0012_financial_event_keys.sql`, `0013_legacy_terminal_order_audit.sql` |
| `IntegrityViolationException` (Gate 6) | `core/error/IntegrityViolationException.kt` |
| `ApiErrorType` ↔ `AppErrorCode` mapping | `core/audit/KisApiErrorMapper.kt` |

Correlation columns:

- `trade_audit_logs.operation_id` INTEGER nullable, indexed, **soft reference** (no FK). Audit rows outlive operations in some retention scenarios and must never be blocked or cascaded by operation lifecycle.
- `api_error_logs.operation_id` INTEGER nullable, indexed, soft reference (different retention: 90 days vs 400 days).
- `operational_events.operation_id` has a real FK (RESTRICT): event detail (90 days hot) is always purged before its operation summary (400 days hot).
- Existing rows keep `NULL`. Historical rows are never rewritten.

### 20.2 Runtime status after Gate 5

Changed (20.4):

- Run Now, `ForwardTestWorker`, and Retry Failed Cycle execute only through `ForwardTestExecutionCoordinator`.
- Every invocation writes one `forward_operations` row plus lifecycle / run / sync / cycle events.
- `operation_id` is written on `trade_audit_logs` and `api_error_logs` rows created inside an operation.

Unchanged:

- Auto Forward Test still uses the existing periodic WorkManager request (1 day, `CONNECTED`, `KEEP`); the Auto flag check, 18:00 Asia/Seoul cutoff, and WorkManager retry backoff are untouched.
- Trading math (fills, sizing, commission / tax, snapshots) is untouched.
- `api_error_logs` still uses the 7-day rolling cleanup (D-141). The 90-day target applies when the lifecycle gate lands.
- `trade_audit_logs` is still never deleted (D-138). The 400-day hot / 5-year archive target applies only after verified archiving exists.
- Audit writes were still outside the business transaction after Gate 5 (section 11); resolved in Gate 6 (20.5).

### 20.3 Known deviations to close in later gates

| Deviation | Standard section | Target gate |
| --- | --- | --- |
| Auto ON can execute immediately (periodic work, no initial delay) | 5, 7 | scheduler + single-flight |
| No single-flight between Run Now and Worker | 7 | **RESOLVED** — Phase 11 / Gate 5 (see 20.4.2); also covers Retry Failed Cycle |
| Orchestrator returns only the last Run's result | 5, 6 | **RESOLVED** — Phase 11 / Gate 5: aggregate outcome across all runs (see 20.4.5) |
| A non-retryable block stops later Runs | 5, 6 | **Retained by decision** (Gate 5): later runs are recorded `SKIPPED` / `PRIOR_RUN_BLOCKED`; changing isolation needs its own gate |
| Orphan `RUNNING` operation after process death (no reconciliation; replay of the same key does not re-execute) | 7, 12 | **RESOLVED** — Phase 11 / Gate 7A: closed as `FAILED` / `PROCESS_INTERRUPTED` on app start (see 20.8) |
| `KisForwardMarketDataGateway` catches generic `Exception` as retryable `NETWORK_FAILURE`; maps local `HistoricalSyncErrorKind.INVALID_DATE_RANGE` / `NO_LATEST_BAR` to retryable `NETWORK_FAILURE` | 4.3, 6 | **RESOLVED** — Phase 11 / Gate 3, commit `f2abe55` (see 20.3.1) |
| Gateway collapses KIS `BUSINESS` / `MALFORMED_RESPONSE` / `MAPPING_FAILURE` / local `INVALID_SYMBOL` / `INVALID_DATE_RANGE` into retryable `NETWORK_FAILURE`; `api_error_logs.error_type` misleading (`NETWORK_TIMEOUT` for local and unexpected failures, `KIS_BUSINESS_ERROR` for rate limit and local validation); gateway appends a duplicate row for failures the repository already recorded | 4.3, 6, 9 | **RESOLVED** — Phase 11 / Gate 4 (see 20.3.2) |
| **OPEN / DEFERRED:** `KisMarketRepositoryImpl` catch-all may classify an unexpected local defect as `MALFORMED_RESPONSE`. The gateway preserves the repository-provided classification. Repository-level refinement needs its own impact analysis | 4.3, 6 | later bounded gate |
| API error logging / 7-day cleanup wrapped in discarded `runCatching` (still present after Gate 5) | 6 | instrumentation + isolation |
| `KisAuthException` has no typed kind; token network failure surfaces as auth failure | 4.3 | audit atomicity + retry |
| Audit written outside business transaction; `ORDER_REJECTED` / `ORDER_CANCELLED` not emitted | 11 | **RESOLVED** — Phase 11 / Gate 6 for evaluation, order create / skip / reject / cancel, and fill (see 20.5) |
| Executions / cash ledger lack canonical unique event keys | 12 | **RESOLVED** — Phase 11 / Gate 6, Room v10 / PostgreSQL `0012` (see 20.5) |
| KIS `msg1` free text appended to `api_error_logs.safe_message` | 17 | instrumentation + isolation |
| Retention / archive / purge jobs absent | 13–16 | archive / retention / redaction |

#### 20.3.1 Resolved: forward gateway classification (Phase 11 / Gate 3)

`syncUniverseTo` and `prepareHistory` route local and unrecognised failures through `AppErrorMapper`:

| Gateway input | `MarketSyncOutcome.errorCode` | Category | `retryable` |
| --- | --- | --- | --- |
| `HistoricalSyncErrorKind.INVALID_DATE_RANGE` | `INTERNAL_INVARIANT_VIOLATION` | INVARIANT | false |
| `HistoricalSyncErrorKind.NO_LATEST_BAR` | `INTERNAL_INVARIANT_VIOLATION` | INVARIANT | false |
| `HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND` | `DATA_INTEGRITY_ERROR` (unchanged) | INVARIANT | false |
| unrecognised exception | `UNEXPECTED_EXCEPTION` | UNEXPECTED | false |
| raw `SocketTimeoutException` / `IOException` | `NETWORK_FAILURE` (unchanged) | TRANSIENT | true |
| `CancellationException` | rethrown, never classified | — | — |

`retryable` equals `AppErrorCode.isRetryableAutomatically`. The unrecognised-exception message is the catalog safe message plus the exception simple class name; `Throwable.message` is never used. `KisMarketException` branches were unchanged in Gate 3 (aligned in Gate 4, 20.3.2). Worker retry logic is unchanged: it still returns `Result.retry()` only when `retryable` is true.

#### 20.3.2 Resolved: KIS error semantics and API error taxonomy (Phase 11 / Gate 4)

Final `api_error_logs.error_type` taxonomy (PostgreSQL `0010`, Kotlin `ApiErrorType`): `NETWORK_TIMEOUT`, `HTTP_ERROR`, `AUTH_ERROR`, `KIS_BUSINESS_ERROR`, `MALFORMED_RESPONSE`, `MASTER_DOWNLOAD_ERROR`, `RATE_LIMIT`, `LOCAL_INVARIANT`, `UNEXPECTED`. The first six are unchanged; historical rows are never rewritten. `AppErrorCode` remains the detailed source; `error_type` is derived from it (`KisApiErrorMapper.fromAppErrorCode`).

| Source | `AppErrorCode` | `error_type` | Forward `errorCode` | `retryable` |
| --- | --- | --- | --- | --- |
| Local `INVALID_DATE_RANGE` (sync use case or repository pre-request) | `INTERNAL_INVARIANT_VIOLATION` | `LOCAL_INVARIANT` | `INTERNAL_INVARIANT_VIOLATION` | false |
| `NO_LATEST_BAR` | `INTERNAL_INVARIANT_VIOLATION` | `LOCAL_INVARIANT` | `INTERNAL_INVARIANT_VIOLATION` | false |
| Local `INVALID_SYMBOL` (not 6 digits, before the request) | `INTERNAL_INVARIANT_VIOLATION` | `LOCAL_INVARIANT` | `INTERNAL_INVARIANT_VIOLATION` | false |
| `INSTRUMENT_NOT_FOUND` | `DATA_INTEGRITY_ERROR` | `LOCAL_INVARIANT` | `DATA_INTEGRITY_ERROR` | false |
| Unrecognised gateway exception | `UNEXPECTED_EXCEPTION` | `UNEXPECTED` | `UNEXPECTED_EXCEPTION` | false |
| Provider `rt_cd != 0` (incl. provider symbol rejection), not `EGW00201` | `KIS_BUSINESS_ERROR` | `KIS_BUSINESS_ERROR` | `KIS_BUSINESS_ERROR` | false |
| Provider payload malformed / numeric `MAPPING_FAILURE` | `KIS_MALFORMED_RESPONSE` | `MALFORMED_RESPONSE` | `KIS_MALFORMED_RESPONSE` | false |
| `EGW00201` after the bounded retry (3 attempts, 61 s wait) | `KIS_RATE_LIMIT` | `RATE_LIMIT` | `NETWORK_FAILURE` | true |
| Transport timeout | `NETWORK_TIMEOUT` | `NETWORK_TIMEOUT` | `NETWORK_FAILURE` | true |
| Network unavailable / HTTP 5xx / other non-2xx | `KIS_SERVER_ERROR` | `HTTP_ERROR` | `NETWORK_FAILURE` | true |
| HTTP 401 / token or credential failure | `AUTH_REQUIRED` | `AUTH_ERROR` | `AUTH_REQUIRED` | false |
| `CancellationException` | rethrown, never classified or recorded | — | — | — |

Default retry semantics: only `TRANSIENT` codes and `KIS_SERVER_ERROR` retry automatically (`AppErrorCode.isRetryableAutomatically`); every other external, local, security, and unexpected failure is non-retryable. Retryable forward results keep the legacy `NETWORK_FAILURE` code; non-retryable results carry the canonical code name.

Duplicate provider/API error logging must not occur merely because multiple architecture layers observe the same exception. `KisMarketRepositoryImpl` records each provider/transport failure attempt once (a rate-limited retry sequence therefore records one row per attempt). The gateway records only failures that originate at or above it: local `INVALID_SYMBOL` / `INVALID_DATE_RANGE`, `MAPPING_FAILURE`, local sync invariants, and unrecognised exceptions.

### 20.4 Runtime operation wiring (Phase 11 / Gate 5)

#### 20.4.1 One coordinator for every state-mutating Forward Test entry point

`ForwardTestExecutionCoordinator` (Hilt singleton) is the only runtime path into `ForwardTestOrchestrator` (through the `ForwardRunExecutor` interface):

| Entry point | Coordinator method | Execution context |
| --- | --- | --- |
| Run Now (`ForwardTestViewModel.runNow`) | `runManualNow()` | app-scoped `executionScope`; the ViewModel only awaits, so leaving the screen does not cancel the operation |
| `ForwardTestWorker.doWork` | `runWorker(workId, runAttemptCount)` | the Worker's own coroutine, so WorkManager stop / cancellation semantics are unchanged |
| Retry Failed Cycle (`ForwardTestViewModel.retryFailedCycle`) | `retryFailedCycle(RetryFailedCycleTarget)` | app-scoped `executionScope` |

Supported `operation_kind` values: `FORWARD_RUN`, `RETRY_FAILED_CYCLE`. `trigger` records who started the invocation; `operation_kind` records what it does (section 7).

The coordinator owns, per invocation: the trigger and kind, the operation key, operation-level `through_date` capture (`ForwardTestClock.throughDate()`, clamped per run to its end date, which equals the previous per-run clock clamp), `startOperation`, the single-flight guard, the aggregate result, `finishOperation`, and lock release.

Order: create or resolve the operation row → acquire the guard → execute → finish → release.

The Worker keeps its Auto flag check before calling the coordinator. The legacy orchestrator methods (`runForwardTests`, `runSingleStrategyRun`, `retryFailedCycle`) remain for tests and are not called by any runtime entry point.

#### 20.4.2 Single-flight

- Mechanism: one process-wide `kotlinx.coroutines.sync.Mutex` in the singleton coordinator, acquired with `tryLock()` (never waits) and released in `finally`.
- Manual/manual, manual/worker, worker/worker, and every combination with Retry Failed Cycle are blocked the same way.
- An overlapping invocation still gets its own durable row: `status = BLOCKED`, `final_code = ALREADY_RUNNING`, `safe_message = "Another Forward Test operation is already running"`, with `OPERATION_STARTED` / `OPERATION_FINISHED` and no execution. A blocked retry never touches its target cycle. The Worker returns `Result.retry()` for `ALREADY_RUNNING` (WorkManager backoff unchanged).
- Cancellation: `CancellationException` is rethrown; before rethrowing, the operation is finished `FAILED` / `CANCELLED` under `NonCancellable`, and the lock is released.
- Scope: process-local. Cross-process execution is not possible for this app (single process).

#### 20.4.3 Operation key semantics

| Key | Semantics |
| --- | --- |
| `manual:<request_id>` | Run Now; `request_id` is a fresh UUID per tap, so each tap is its own operation |
| `manual-retry:<request_id>` | Retry Failed Cycle; fresh UUID per tap. Never derived from `run_id` + cycle date |
| `worker:<work_id>:<through_date>:<attempt>` | **Interim** key for the current `PeriodicWorkRequest` |

Why the Worker key is interim: WorkManager reuses the same `work_id` for every period of a periodic request and resets `runAttemptCount` after each period, so `worker:<work_id>:<attempt>` (D-145) would collide across days. Adding the operation-level `through_date` separates periods; a redelivery of the same `work_id` + `through_date` + `attempt` resolves to the same row and does not re-execute (the stored row decides the Worker result).

Known interim limitation: if periodic drift puts two periods in the same 18:00-to-18:00 KST window (e.g. 18:30 on day N and 17:50 on day N+1, both through-date N) with the same attempt, the second resolves to the first row instead of executing. The first already processed that through-date, so no market date is skipped.

Future canonical key: `worker:<schedule_instance_id>:<attempt>`, with `schedule_instance_id` such as `auto:2026-10-01:0730:KST`. It is introduced when the later scheduler gate replaces periodic work. The scheduler is **not** redesigned in Gate 5.

#### 20.4.4 Event coverage

| Event | Coverage |
| --- | --- |
| `OPERATION_STARTED` / `OPERATION_FINISHED` | every invocation, including `ALREADY_RUNNING`, failures, and cancellation; finish carries status, safe code / message, counts, `elapsed_ms` (NULL for an operation recovered as `PROCESS_INTERRUPTED`, 20.8) |
| `RUN_RESULT` | exactly one per selected run: `PROCESSED`, `SKIPPED`, `BLOCKED`, `FAILED`, or `NO_OP`, with a reason code (`ForwardOutcomeReason` name or, for failures, the canonical error code) |
| `MARKET_SYNC_RESULT` | exactly one per attempted run sync: `SUCCESS` / `FAILED`, failure code, `market_date` = through date, message with requested start / through and inserted / updated / unchanged counts, `elapsed_ms`. No payload |
| `CYCLE_STARTED` / `CYCLE_FINISHED` | one pair per cycle attempt (`attempt` = `forward_test_cycles.attempt_count`), with run, cycle, `market_date`; finish carries `COMPLETE` / `FAILED`, reason code, stage, `elapsed_ms`. An exception inside a cycle writes `CYCLE_FINISHED` `FAILED` with the canonical code before propagating |

Evidence writes are not swallowed: a failure to persist evidence fails the operation (`FAILED`).

#### 20.4.5 Aggregate outcome

- `runs_considered` = READY / RUNNING runs selected at operation start (for a retry: the target run).
- `runs_processed` = runs whose own processing started (market sync attempted, or the retried cycle attempted).
- `runs_skipped` = runs intentionally not processed (`SKIPPED` / `NO_OP` before processing), including `PRIOR_RUN_BLOCKED`. The blocker is not counted as skipped.
- Status: no problem and some run `PROCESSED` → `SUCCEEDED`; nothing processed and no problem → `NO_OP`; a problem (`BLOCKED` / `FAILED` run) after meaningful progress → `PARTIAL`; a problem without progress → `BLOCKED`; unexpected exception or cancellation → `FAILED`.
- `final_code`: the decisive problem's reason (first non-retryable, else first retryable); for `NO_OP` the first run's reason; `NO_ELIGIBLE_RUNS` when no run is selected.
- Run isolation is unchanged: a non-retryable block still stops later runs. The blocker gets `BLOCKED` with its canonical reason (e.g. `MISSING_POLICY`, `AUTH_REQUIRED`, `EMPTY_UNIVERSE`, `PREVIOUS_FAILED_CYCLE`). Each later run gets `RUN_RESULT` `SKIPPED` / `PRIOR_RUN_BLOCKED` with the fixed message "Skipped because an earlier run blocked the operation", never `FAILED` / `BLOCKED`. No `blocked_by_run_id` column.
- Worker result derives from the aggregate: no problem → `success`; decisive problem retryable → `retry`; non-retryable → `failure` (unchanged for a non-retryable block); unexpected exception → `failure`. A replayed key derives the result from the stored row.
- Run Now / Retry display: identical to the previous single-run result when one run is selected. With several runs the display is the decisive problem, else the merged processed dates (previously the last run's result).

#### 20.4.6 Retry Failed Cycle

- Target resolution: explicit `expectedCycleId`, else `marketDate`, else the run's oldest `FAILED` cycle.
- Target validation (target is never mutated): missing → `NO_OP` / `TARGET_CYCLE_NOT_FOUND`; not `FAILED` (stale) → `NO_OP` / `TARGET_CYCLE_NOT_FAILED`; belongs to another run → `BLOCKED` / `TARGET_CYCLE_RUN_MISMATCH`; run missing → `BLOCKED` / `RUN_NOT_FOUND`.
- A successful retry updates the same cycle row (`attempt_count` + 1, no new cycle) and continues the run through the operation through-date, as before.
- Correlation: operation → target run (`RUN_RESULT.run_id`) → target cycle (`CYCLE_STARTED.cycle_id`, `attempt:<n>`) → evaluation / order / execution audit (`trade_audit_logs.operation_id`) → API errors (`api_error_logs.operation_id`).

#### 20.4.7 Correlation propagation

The coordinator runs the executor inside `withContext(ForwardOperationContext(operation_id))`. `TradeAuditLogService.append` and `ApiErrorLogService.record` read `currentForwardOperationId()` and stamp new rows. Rows written outside an operation keep `NULL`. An idempotent audit replay returns the existing row unchanged; historical rows are never rewritten.

#### 20.4.8 Error handling

- `CancellationException` is always rethrown (orchestrator cycle path, coordinator, ViewModel).
- Any other exception finishes the operation `FAILED` with `AppErrorMapper.fromThrowable` code (e.g. `UNEXPECTED_EXCEPTION`) and the catalog safe message plus the exception simple class name. `Throwable.message` is never persisted or displayed.
- The ViewModel shows the operation's safe display result. Only a failure to record the operation itself reaches the ViewModel, which shows a safe catalog message instead of crashing.

#### 20.4.9 Remaining gaps (not resolved by Gate 5)

- **Audit atomicity** (section 11): unchanged by Gate 5; resolved in Gate 6 (20.5).
- **Scheduler redesign**: periodic work, immediate first execution on Auto ON, and the interim Worker key remain until the scheduler gate.
- **Orphan `RUNNING` operations**: if the process dies mid-operation the row stays `RUNNING`; a redelivery of the same Worker key returns `retry` without re-executing, and a new period / tap creates a new row. **RESOLVED in Gate 7A (20.8).**
- **Retry precedence**: when an earlier run was retryable-blocked and a later run succeeded, the Worker now returns `retry` (previously the last run's success decided). Non-retryable blocks still decide `failure` first.
- **Retention / archive / Operations UI**: not implemented.

### 20.5 Audit atomicity and financial idempotency (Phase 11 / Gate 6)

#### 20.5.1 Transaction boundaries

| Logical operation | One Room transaction | Owner |
| --- | --- | --- |
| Evaluation | `stock_evaluations` + `stock_evaluation_details` + `RULE_TRIGGERED` (rule decisions) + `EVALUATION_DECIDED` | `StrategyEvaluationRepository.persistSnapshot` (audit via `inSameTransaction`, called by `EvaluateStrategyRunUseCase`) |
| Order decision | existing-order check + position read + either (`orders` row + `ORDER_CREATED`) or `ORDER_SKIPPED` | `ProcessEvaluationUseCase` |
| Rejection | re-read order + `REJECTED` (quantity 0) + `ORDER_REJECTED` | `VirtualFillService.reject` |
| Run-end finalization (Gate 6.1) | every pending order: re-read + `CANCELLED` (`cancelled_at` from the orchestrator clock) + `ORDER_CANCELLED`; then run `COMPLETED` | `ProcessPendingOrdersUseCase.finalizeRunEnd` (outer transaction via `VirtualFillService.inTransaction`; `VirtualFillService.cancelPending` joins it) |
| Execution | re-read order + replay check + `executions` + cash ledger group + `positions` + order `VIRTUAL_FILLED` + `EXECUTION_FILLED` | `VirtualFillService.executeBuy` / `executeSell` |

Outside the execution transaction, by design: `portfolio_daily_snapshots` (recomputed from committed state, unique per run/date). Gate 6 also left the run `COMPLETED` update outside the cancellations; Gate 6.1 moved it into the run-end transaction (20.6.2). Snapshot, order, fill price, sizing, fee / tax, and slippage math are unchanged.

Crash windows closed: evaluation committed without `EVALUATION_DECIDED`; order committed without `ORDER_CREATED`; fill committed without `EXECUTION_FILLED`; rejection / cancellation without any audit. An injected failure at any audit or ledger insert rolls back the whole logical operation (`AuditAtomicityFinancialIdempotencyTest`).

#### 20.5.2 Canonical financial keys

| Table | Key | Constraint |
| --- | --- | --- |
| `executions` | `paper:order:<order_id>:fill:1` | `execution_key` TEXT NOT NULL, `uq_executions_execution_key` |
| `cash_ledger` | `run:<run>:initial-deposit` | `event_key` TEXT NOT NULL, `uq_cash_ledger_event_key` |
| `cash_ledger` | `execution:<id>:buy-principal`, `execution:<id>:buy-commission` | same |
| `cash_ledger` | `execution:<id>:sell-proceeds`, `execution:<id>:sell-commission`, `execution:<id>:sell-tax` | same |

Ledger rows are written only where they existed before: commission / tax rows only when the amount is > 0. One execution produces exactly one ledger group, referenced by `reference_type = EXECUTION`, `reference_id = executions.id`.

#### 20.5.3 Replay and conflicts

- Fill replay: inside the transaction the order is re-read and the execution is looked up by key. Same order state and identical fill (order, price, quantity, commission, tax, slippage, `executed_at`) → `ALREADY_FILLED` with no financial mutation. Any disagreement under an existing key (order not `VIRTUAL_FILLED`, different values) → `EXECUTION_IDEMPOTENCY_CONFLICT`; an order already `VIRTUAL_FILLED` with no execution under its key → `FILLED_ORDER_WITHOUT_EXECUTION` (Gate 6.2, 20.7). Both are severity `FINANCIAL_INTEGRITY` (Gate 6 used `DATA_INTEGRITY_ERROR`), and the transaction aborts.
- Ledger replay: same key with identical run, type, amount, date, and reference → existing row, no cash mutation; different content → `LEDGER_MISMATCH` / `LEDGER_EVENT_KEY_CONFLICT`, abort.
- Audit replay: same key with the same run, event type, and evaluation / order / execution ids → existing row; otherwise `AUDIT_EVENT_KEY_CONFLICT` (`INTERNAL_INVARIANT_VIOLATION`), abort.
- Nothing is caught to continue; the coordinator finishes the operation `FAILED` with the mapped code (20.4.8).

#### 20.5.4 Legacy reconciliation

- Missing `EVALUATION_DECIDED` / `ORDER_CREATED` / `EXECUTION_FILLED` rows are appended on the replay paths (`ALREADY_EVALUATED`, `ORDER_ALREADY_EXISTS`, `ALREADY_FILLED`) by deterministic key, with `reason_code = LEGACY_AUDIT_RESTORED` and `operation_id = NULL` (the reconciling operation did not produce the event). Business rows are never modified.
- Not reconciled, because the facts are not stored: legacy `RULE_TRIGGERED` details. Gate 6.1 (20.6) adds provable `decision_source` recovery and the legacy `REJECTED` / `CANCELLED` order audit.
- Migration keys (Room `MIGRATION_9_10`, PostgreSQL `0012`) are derived only from provable identity: executions by `order_id` (one execution per order), ledger rows by type + reference + `orders.side`. Any unrecognised shape or duplicate derived key aborts the migration; amounts, balances, ids, and timestamps are copied verbatim. The real Phase 10 DB copy migrates with all counts, execution rows, ledger rows, and reconstructed cash unchanged.

#### 20.5.5 Partial-fill compatibility

Current paper trading has exactly one `VIRTUAL_FILLED` execution per order (full fill at the next trading day open). Future broker trading may report several executions per order; they use `:fill:<n>` (or a broker execution id) under the same unique `execution_key`. `UNIQUE (order_id)` is deliberately not used. Partial fills are **not** implemented.

#### 20.5.6 Remaining gaps (not resolved by Gate 6)

- Orphan `RUNNING` operations and post-process-death reconciliation (20.4.9). **RESOLVED in Gate 7A (20.8).**
- Scheduler redesign and archive / retention / Operations UI.
- **RESOLVED in Gate 6.1 (20.6.2):** `finalizeRunEnd` committed cancellations and the run `COMPLETED` transition separately.
- **RESOLVED in Gate 6.1 (20.6.1):** financial conflicts used `DATA_INTEGRITY_ERROR` (reported as `CRITICAL`). `LEDGER_MISMATCH` / `EXECUTION_IDEMPOTENCY_CONFLICT` now carry `FINANCIAL_INTEGRITY`; `DUPLICATE_EXECUTION` is intentionally not added.
- The `KisAuthException` typed-kind deviation (20.3) is unrelated to audit atomicity and remains open.

### 20.6 Financial integrity closure (Phase 11 / Gate 6.1)

#### 20.6.1 Exact replay vs idempotency conflict

| Situation | Result |
| --- | --- |
| Same `execution_key`, identical order state and fill facts | `ALREADY_FILLED`, no mutation (normal, not an error) |
| Same `execution_key`, persisted facts differ from the attempted logical fill (values, order not `VIRTUAL_FILLED`, quantity differs) | `EXECUTION_IDEMPOTENCY_CONFLICT` (`EXECUTION_REPLAY_MISMATCH` / `EXECUTION_ORDER_STATE_MISMATCH`) |
| Order `VIRTUAL_FILLED` without an execution under its key | `FILLED_ORDER_WITHOUT_EXECUTION` (Gate 6.2, 20.7) |
| Same ledger `event_key`, identical facts | existing row, no cash mutation |
| Same ledger `event_key`, conflicting values | `LEDGER_MISMATCH` (`LEDGER_EVENT_KEY_CONFLICT`) |
| Latest `balance_after` ≠ sum of `amount` for the run, checked before every ledger append | `LEDGER_MISMATCH` (`LEDGER_BALANCE_MISMATCH`); nothing is appended |
| Running reconstruction disagrees with a row's `balance_after`, or goes negative (`CashLedgerService.reconstructCash`) | `LEDGER_MISMATCH` (`LEDGER_RECONSTRUCTION_MISMATCH`) |

All three codes: category `INVARIANT`, severity `FINANCIAL_INTEGRITY`, retry `NONE`, user action required, operation action `ABORT_OPERATION` (the catalog convention for every `INVARIANT` code), audit required. `AppErrorMapper` keeps the exception's code, so `SafeAppError.severity` reports `FINANCIAL_INTEGRITY` instead of collapsing to `CRITICAL`. The exception message is only the canonical reason code. The ledger balance check adds one `SUM(amount)` read per append; cash arithmetic is unchanged.

#### 20.6.2 Run-end finalization boundary

`ProcessPendingOrdersUseCase.finalizeRunEnd(run, market_date, finalized_at)` is one Room transaction:

1. every `PENDING_EXECUTION` order of the run → `CANCELLED` (`cancelled_at = finalized_at`), each with one `ORDER_CANCELLED` (`reason_code = RUN_END_REACHED`);
2. the run → `COMPLETED`.

Either everything commits or nothing does. The qualifying orders (`PENDING_EXECUTION` of the run) are unchanged, and the orchestrator still calls it only when the processed market date equals the run end date. A failure at the first, middle, or last cancellation audit, or at the run update, rolls back every cancellation, every new audit, and the run transition; the retry then succeeds once with no duplicate audit (`AuditAtomicityFinancialIdempotencyTest`).

#### 20.6.3 Legacy terminal-order audit reconciliation

Room `MIGRATION_10_11` / PostgreSQL `0013` (data-only; no schema or CHECK change) append exactly one audit for each `REJECTED` / `CANCELLED` order that has none:

| Order | Event / key | `reason_code` |
| --- | --- | --- |
| `REJECTED`, `quantity = 0`, side `BUY` | `ORDER_REJECTED` / `order:<id>:rejected` | `INSUFFICIENT_CASH` |
| `REJECTED`, `quantity = 0`, side `SELL` | same | `NO_POSITION_TO_SELL` |
| `REJECTED`, any other shape | same | `LEGACY_REASON_UNKNOWN` |
| `CANCELLED`, `cancelled_at` set | `ORDER_CANCELLED` / `order:<id>:cancelled` | `RUN_END_REACHED` |
| `CANCELLED`, `cancelled_at` NULL | same | `LEGACY_REASON_UNKNOWN` |

Proof from repository history (no market / account state is recalculated): before Gate 6 the only `REJECTED` writers were `ProcessPendingOrdersUseCase.fillBuy` (no affordable quantity → `INSUFFICIENT_CASH`) and `fillSell` (no position → no-position), both writing `quantity = 0`, selected by the immutable order side. The only `CANCELLED` writer was run-end finalization (since `c7c3421`), which always set `cancelled_at`. No raw SQL ever wrote either status. Orders not matching the writer's signature get `LEGACY_REASON_UNKNOWN`.

Other fields: `strategy_run_id`, `instrument_id`, `evaluation_id`, `order_id` from the order; `reason_text = "LEGACY_AUDIT_RESTORED: pre-Gate-6 <status> order had no audit"`; `market_date`, `decision_source`, `operation_id` NULL (not provable). Rules: an order with its audit (by key, or by order + event type) is skipped; orders and existing audit rows are never modified; rerunning changes nothing (`BJStockMigrations.reconcileLegacyTerminalOrderAudits` is idempotent). The real Phase 10 DB copy has no terminal orders, so it gains no rows.

#### 20.6.4 Legacy evaluation `decision_source`

`decision_source` values: `SIGNAL_RULE`, `FACTOR_STRATEGY`, and NULL **only** for legacy evidence whose source cannot be reconstructed. NULL is not a third decision source; on a restored row `reason_code = LEGACY_AUDIT_RESTORED` explains why it is NULL. `LEGACY_UNKNOWN` is not a decision source and is not added; the PostgreSQL `ck_trade_audit_logs_decision_source` CHECK (0008) is unchanged.

When `ALREADY_EVALUATED` restores a missing `EVALUATION_DECIDED`:

| Proof in immutable records | Restored `decision_source` |
| --- | --- |
| a `RULE_TRIGGERED` audit exists for the evaluation (written only for rule decisions) | `SIGNAL_RULE` |
| the run's strategy version has no enabled signal rules (rules are editable only on DRAFT versions; evaluation requires ACTIVE) | `FACTOR_STRATEGY` |
| neither | NULL |

Live `EVALUATION_DECIDED` rows always carry the computed source (`StrategyEvaluationResult.decisionSource` is non-null); a live row never uses NULL. The value is an enum name, so an empty string is impossible.

#### 20.6.5 Append-only historical rows

Existing audit rows are never updated, including restored rows whose `decision_source` is NULL; they remain legacy evidence. Only rows written after Gate 6.1 use the corrective rules above. The real Phase 10 DB copy has no empty-string `decision_source` and every `EVALUATION_DECIDED` row there already carries a source.

#### 20.6.6 Remaining gaps (not resolved by Gate 6.1)

- Scheduler redesign, archive / retention, Operations UI (20.4.9, 20.5.6). Orphan `RUNNING` operations were resolved later in Gate 7A (20.8).
- `market_date` of reconciled terminal-order audits stays NULL (not persisted on the order).

### 20.7 Execution invariant taxonomy (Phase 11 / Gate 6.2)

Three separate financial-integrity concepts, each with its own code (all `INVARIANT`, `FINANCIAL_INTEGRITY`, retry `NONE`, user action required, `ABORT_OPERATION`, audit required):

| Code | Meaning | Raised when |
| --- | --- | --- |
| `LEDGER_MISMATCH` | cash / ledger financial inconsistency | ledger key conflict, balance ≠ sum of amounts, reconstruction mismatch (20.6.1) |
| `EXECUTION_IDEMPOTENCY_CONFLICT` | the same logical execution identity conflicts on replay | an execution already exists under the `execution_key`, but the order association, order state, quantity, price, or other immutable fill facts differ |
| `FILLED_ORDER_WITHOUT_EXECUTION` | the persisted order / execution invariant is broken | the order is `VIRTUAL_FILLED` and no execution exists under its `execution_key` |

`FILLED_ORDER_WITHOUT_EXECUTION` is not an idempotency conflict: there is no existing execution identity to conflict with. `VirtualFillService` raises it inside the fill transaction before any mutation, so no execution, ledger row, position, order change, or audit is written. `EXECUTION_IDEMPOTENCY_CONFLICT` is not broadened. An exact replay (same key, identical facts) remains `ALREADY_FILLED` with no mutation and no error. No schema change: error codes are persisted only as free text (`final_code`, `reason_code`), and `api_error_logs.error_type` maps the new code to the existing `LOCAL_INVARIANT`.

Gate 6.1 decisions kept unchanged: reconciled terminal-order audits keep `market_date` NULL when the historical market date cannot be proven (never inferred from `created_at` / `cancelled_at` / `updated_at`); the `SUM(amount)` check before each ledger append stays; `finalizeRunEnd` keeps one shared timestamp for cancellation and run completion.

### 20.8 Interrupted operation recovery (Phase 11 / Gate 7A)

#### 20.8.1 Definition

An **interrupted (orphan) operation** is a `forward_operations` row with `status = RUNNING` that was started by an earlier app process which ended (for example killed by Android) before the operation reached a terminal result. It is an operational outcome, not a KIS, trading, domain, or user-cancellation failure, so it is recorded with the `ForwardOutcomeReason` `PROCESS_INTERRUPTED`, not an `AppErrorCode`.

#### 20.8.2 Process-start cutoff

`BJStockApplication` captures `processStartCutoff = Instant.now()` truncated to milliseconds when the Application object is constructed, before any work of the process can start. Recovery selects only:

```text
status = 'RUNNING' AND started_at < processStartCutoff   (strict)
```

A `RUNNING` row started at or after the cutoff belongs to the current process (for example a Worker that WorkManager launched during startup) and is never touched. Truncation matters because `started_at` is stored as epoch millis: an operation started by this process is always stored at or after the cutoff. No "older than N minutes" timeout is used.

#### 20.8.3 Terminalization

`ForwardOperationLogService.recoverInterruptedOperations(processStartCutoff)`, per orphan:

| Field | Value |
| --- | --- |
| `status` | `RUNNING` → `FAILED` |
| `final_code` | `PROCESS_INTERRUPTED` |
| `safe_message` | fixed: "Operation was interrupted before completion and recovered on app start" (never `Throwable.message`) |
| `finished_at` | recovery time |
| `elapsed_ms` | NULL |
| `runs_considered` / `runs_processed` / `runs_skipped` / `cycles_completed` / `cycles_failed` | preserved as stored; never recomputed or fabricated |

`elapsed_ms` stays NULL because the real execution duration is unknowable after process death; the time until the app restarted must not be presented as execution duration.

Each recovered operation gets one `OPERATION_FINISHED` event under the normal key `op:<operation_id>:finished`: `result = FAILED`, `reason_code = PROCESS_INTERRUPTED`, the same fixed message, `elapsed_ms` NULL, `market_date` = the operation through-date, `created_at` = recovery time.

#### 20.8.4 Atomicity and idempotency

All selected orphans and their `OPERATION_FINISHED` events commit in **one** Room transaction, or nothing changes: there is never a `FAILED` row without its finish event, or a finish event on a still-`RUNNING` row. A failure (event insert, row update, or the invariant below) rolls back every transition of that recovery pass; the next app start retries. A second pass selects nothing (rows are no longer `RUNNING`) and creates no duplicate finish event.

#### 20.8.5 Inconsistent evidence

A row that is `RUNNING` but already has an `OPERATION_FINISHED` event is a data-integrity inconsistency. Recovery never overwrites or ignores it: it aborts with `IntegrityViolationException` (`DATA_INTEGRITY_ERROR`, reason `RUNNING_OPERATION_ALREADY_FINISHED`) and nothing changes. None of the real Phase 10 evidence databases contains `forward_operations` (all are Room v7), so no such row exists there.

#### 20.8.6 Existing events

Events written before the interruption (`OPERATION_STARTED`, `MARKET_SYNC_RESULT`, `RUN_RESULT`, `CYCLE_STARTED`, ...) are never deleted or rewritten. Recovery appends only the missing `OPERATION_FINISHED`. A `CYCLE_STARTED` without `CYCLE_FINISHED` legitimately remains; no `CYCLE_FINISHED` or `RUN_RESULT` is fabricated. Cycle rows (`forward_test_cycles`) and business data are not touched; the orchestrator's existing cycle rules handle them on the next execution.

#### 20.8.7 Startup ordering

```text
process start (cutoff captured)
  ↓
recover interrupted operations
  ↓
reconcile persisted Auto schedule (ForwardTestScheduler.reconcileOnAppStart, unchanged)
  ↓
background maintenance (7-day API error cleanup)
```

The sequence runs on the application IO scope (`runAppStartSequence`). A recovery failure is contained and never prevents schedule reconciliation. Only the ordering changed: what `reconcileOnAppStart` schedules, the `PeriodicWorkRequest`, its constraints, backoff, Auto ON / OFF behavior, and first-run behavior are unchanged. Because the sequence is asynchronous, the cutoff rule (20.8.2) is what protects work started by the current process. Recovery itself never executes a Forward Test and never starts an operation.

#### 20.8.8 Worker replay

A Worker invocation whose key resolves to an existing row with `status = FAILED` and `final_code = PROCESS_INTERRUPTED` returns `WorkerDisposition.RETRY`. The same key is never re-executed; WorkManager's next attempt gets the next attempt identity through the existing Gate 5 key `worker:<work_id>:<through_date>:<attempt>` (unchanged in Gate 7A; `schedule_instance_id` belongs to Gate 7B) and executes normally. A redelivery that arrives before recovery still sees `RUNNING` and also returns `RETRY` (20.4.9). Manual operations need no replay: each tap gets a new UUID key.

#### 20.8.9 Schema

No Room or PostgreSQL schema change. `PROCESS_INTERRUPTED` is stored in the existing free-text `final_code` / `reason_code`. The PostgreSQL CHECKs allow it: `ck_forward_operations_finished_consistency` only ties `RUNNING` to `finished_at IS NULL`, and `elapsed_ms` is nullable.

#### 20.8.10 Remaining gaps (not resolved by Gate 7A)

- Scheduler redesign (Gate 7B): periodic work, immediate first execution on Auto ON, interim Worker key, `schedule_instance_id`.
- A recovery pass aborted by the inconsistency in 20.8.5 is contained at startup but not yet surfaced to the user (no Operations UI).
- Archive / retention, Operations UI.

---

## Related

- `docs/140_FORWARD_TEST_ORCHESTRATION.md`
- `docs/141_FORWARD_TEST_OPERATIONS.md`
- `docs/147_TRADE_AUDIT_LOG.md`
- `docs/148_API_ERROR_LOGGING.md`
- `docs/023_ROOM_SCHEMA_MAPPING.md`
- `docs/060_DECISION_LOG.md` (D-143 – D-164)
