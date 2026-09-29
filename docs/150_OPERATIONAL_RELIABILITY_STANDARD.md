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
| Scheduler rework, retention/archive, Operations UI, orphan-operation reconciliation | **Not implemented** — later Phase 11 gates |

Auto Forward Test scheduling (periodic work, 18:00 Asia/Seoul cutoff), trading math, 7-day API error cleanup, and permanent trade audit are unchanged by Gates 5 and 6.

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
| `INVARIANT` | Internal consistency guarantee violated. | `DUPLICATE_EXECUTION`, `LEDGER_MISMATCH`, `INVALID_STATE_TRANSITION`, `DB_CONSTRAINT_VIOLATION` |
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
| `INTERNAL_INVARIANT_VIOLATION` | INVARIANT | CRITICAL | NONE | yes | ABORT_OPERATION | no |
| `UNEXPECTED_EXCEPTION` | UNEXPECTED | ERROR | NONE | no | ABORT_OPERATION | no |

Safe messages are defined once in `AppErrorCode` and are the only text persisted for a code.

### 4.2 Planned catalog (not yet in code)

Reserved for the idempotency / audit-atomicity gates. Added to `AppErrorCode` only when a real path emits them, with a regression test.

| Code | Category | Severity | Retry | Operation action |
| --- | --- | --- | --- | --- |
| `DUPLICATE_EXECUTION` | INVARIANT | CRITICAL | NONE | ABORT_OPERATION |
| `INVALID_STATE_TRANSITION` | INVARIANT | CRITICAL | NONE | ABORT_OPERATION |
| `DB_CONSTRAINT_VIOLATION` | INVARIANT | CRITICAL | NONE | ABORT_OPERATION |
| `LEDGER_MISMATCH` | INVARIANT | FINANCIAL_INTEGRITY | NONE | ABORT_OPERATION |

Gate 6 does not add these codes. Canonical-key conflicts raise `IntegrityViolationException` carrying an existing code plus a severity: execution / ledger conflicts → `DATA_INTEGRITY_ERROR` with severity `FINANCIAL_INTEGRITY`; audit key conflicts → `INTERNAL_INVARIANT_VIOLATION` (`CRITICAL`). `SafeAppError.severity` still reports the catalog severity of the code (`CRITICAL`).

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
| `IntegrityViolationException` | its `code` (`DATA_INTEGRITY_ERROR` / `INTERNAL_INVARIANT_VIOLATION`) |
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
| order cancellation | status → CANCELLED at run end; **no** `ORDER_CANCELLED` emitted | CANCELLED + `ORDER_CANCELLED` in one transaction per order |
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
| Room v8 + `MIGRATION_7_8`; Room v9 + `MIGRATION_8_9` (`operation_kind`); Room v10 + `MIGRATION_9_10` (financial event keys) | `core/database/BJStockMigrations.kt` |
| PostgreSQL parity | `db/migrations/0009_operational_reliability_foundation.sql`, `0010_api_error_type_taxonomy.sql`, `0011_forward_operation_kind.sql`, `0012_financial_event_keys.sql` |
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
| Orphan `RUNNING` operation after process death (no reconciliation; replay of the same key does not re-execute) | 7, 12 | scheduler / reconciliation gate |
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
| `OPERATION_STARTED` / `OPERATION_FINISHED` | every invocation, including `ALREADY_RUNNING`, failures, and cancellation; finish carries status, safe code / message, counts, `elapsed_ms` |
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
- **Orphan `RUNNING` operations**: if the process dies mid-operation the row stays `RUNNING`; a redelivery of the same Worker key returns `retry` without re-executing, and a new period / tap creates a new row. Reconciliation is a later gate.
- **Retry precedence**: when an earlier run was retryable-blocked and a later run succeeded, the Worker now returns `retry` (previously the last run's success decided). Non-retryable blocks still decide `failure` first.
- **Retention / archive / Operations UI**: not implemented.

### 20.5 Audit atomicity and financial idempotency (Phase 11 / Gate 6)

#### 20.5.1 Transaction boundaries

| Logical operation | One Room transaction | Owner |
| --- | --- | --- |
| Evaluation | `stock_evaluations` + `stock_evaluation_details` + `RULE_TRIGGERED` (rule decisions) + `EVALUATION_DECIDED` | `StrategyEvaluationRepository.persistSnapshot` (audit via `inSameTransaction`, called by `EvaluateStrategyRunUseCase`) |
| Order decision | existing-order check + position read + either (`orders` row + `ORDER_CREATED`) or `ORDER_SKIPPED` | `ProcessEvaluationUseCase` |
| Rejection | re-read order + `REJECTED` (quantity 0) + `ORDER_REJECTED` | `VirtualFillService.reject` |
| Cancellation | re-read order + `CANCELLED` (`cancelled_at` from the orchestrator clock) + `ORDER_CANCELLED`, one transaction per order | `VirtualFillService.cancelPending`, via `ProcessPendingOrdersUseCase.cancelPendingAtRunEnd` from `finalizeRunEnd` |
| Execution | re-read order + replay check + `executions` + cash ledger group + `positions` + order `VIRTUAL_FILLED` + `EXECUTION_FILLED` | `VirtualFillService.executeBuy` / `executeSell` |

Outside the execution transaction, by design: `portfolio_daily_snapshots` (recomputed from committed state, unique per run/date) and the run status update to `COMPLETED` in `finalizeRunEnd`. Snapshot, order, fill price, sizing, fee / tax, and slippage math are unchanged.

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

- Fill replay: inside the transaction the order is re-read and the execution is looked up by key. Same order state and identical fill (order, price, quantity, commission, tax, slippage, `executed_at`) → `ALREADY_FILLED` with no financial mutation. Any disagreement (key present but order not `VIRTUAL_FILLED`, filled order without execution, different values) → `IntegrityViolationException.financial` (`DATA_INTEGRITY_ERROR`, severity `FINANCIAL_INTEGRITY`), and the transaction aborts.
- Ledger replay: same key with identical run, type, amount, date, and reference → existing row, no cash mutation; different content → `LEDGER_EVENT_KEY_CONFLICT`, abort.
- Audit replay: same key with the same run, event type, and evaluation / order / execution ids → existing row; otherwise `AUDIT_EVENT_KEY_CONFLICT` (`INTERNAL_INVARIANT_VIOLATION`), abort.
- Nothing is caught to continue; the coordinator finishes the operation `FAILED` with the mapped code (20.4.8).

#### 20.5.4 Legacy reconciliation

- Missing `EVALUATION_DECIDED` / `ORDER_CREATED` / `EXECUTION_FILLED` rows are appended on the replay paths (`ALREADY_EVALUATED`, `ORDER_ALREADY_EXISTS`, `ALREADY_FILLED`) by deterministic key, with `reason_code = LEGACY_AUDIT_RESTORED` and `operation_id = NULL` (the reconciling operation did not produce the event). Business rows are never modified.
- Not reconciled, because the facts are not stored: legacy `RULE_TRIGGERED` details, the original evaluation `decision_source`, and reasons for legacy `REJECTED` / `CANCELLED` orders.
- Migration keys (Room `MIGRATION_9_10`, PostgreSQL `0012`) are derived only from provable identity: executions by `order_id` (one execution per order), ledger rows by type + reference + `orders.side`. Any unrecognised shape or duplicate derived key aborts the migration; amounts, balances, ids, and timestamps are copied verbatim. The real Phase 10 DB copy migrates with all counts, execution rows, ledger rows, and reconstructed cash unchanged.

#### 20.5.5 Partial-fill compatibility

Current paper trading has exactly one `VIRTUAL_FILLED` execution per order (full fill at the next trading day open). Future broker trading may report several executions per order; they use `:fill:<n>` (or a broker execution id) under the same unique `execution_key`. `UNIQUE (order_id)` is deliberately not used. Partial fills are **not** implemented.

#### 20.5.6 Remaining gaps (not resolved by Gate 6)

- Orphan `RUNNING` operations and post-process-death reconciliation (20.4.9).
- Scheduler redesign and archive / retention / Operations UI.
- `finalizeRunEnd` commits cancellations and the run `COMPLETED` transition separately; a crash between them leaves the run `RUNNING` with its pending orders already cancelled.
- The planned `LEDGER_MISMATCH` / `DUPLICATE_EXECUTION` catalog codes (4.2) are not yet in `AppErrorCode`.
- The `KisAuthException` typed-kind deviation (20.3) is unrelated to audit atomicity and remains open.

---

## Related

- `docs/140_FORWARD_TEST_ORCHESTRATION.md`
- `docs/141_FORWARD_TEST_OPERATIONS.md`
- `docs/147_TRADE_AUDIT_LOG.md`
- `docs/148_API_ERROR_LOGGING.md`
- `docs/023_ROOM_SCHEMA_MAPPING.md`
- `docs/060_DECISION_LOG.md` (D-143 – D-158)
