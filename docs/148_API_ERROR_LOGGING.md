# API Error Logging

Diagnostics-only KIS failure log. Separate from trade audit.

## Purpose

```text
diagnostics only
```

## Retention

```text
Rolling 7-day retention
```

Cleanup deletes rows with `occurred_at` strictly before `now - 7 days`. Invoked on app start and after successful Forward Test orchestrator paths (Worker / Run Now).

Target: 90-day hot retention plus 400-day daily aggregates (`docs/150_OPERATIONAL_RELIABILITY_STANDARD.md`). Not active yet (D-149).

A cleanup failure (`cleanupOrReport`, trigger `APP_START` or `FORWARD_SUCCESS`) is contained: the app keeps starting, scheduler reconciliation and the Auto flag are unaffected, and one safe fallback line is emitted (`docs/150` 20.11.5).

## Evidence write failure

The KIS failure being recorded is the primary error; a failed insert into this table is secondary and never changes the primary's kind, code, retryability, or public message. Writers call `ApiErrorLogService.recordOrReport`, which on failure emits one fixed line through `KisAuthLogger` (`API error evidence write failed: <operation> (<ExceptionSimpleName>)`) and never retries into this table. `Throwable.message` and SQL text are never logged. `CancellationException` is rethrown (`docs/150` 20.11).

## Operation correlation

Room v8 adds nullable `operation_id` (soft reference to `forward_operations.id`). Existing rows stay NULL. Since Phase 11 / Gate 5, rows recorded inside a Forward Test operation carry its id; rows recorded elsewhere stay NULL (`docs/150` 20.4.7).

## Secrets

Never store AppKey, AppSecret, AccessToken, Authorization headers, account numbers, or raw request/response bodies. `ApiErrorLogService.sanitize` redacts secret-like messages.

## Providers / types

Provider: `KIS`

Types (PostgreSQL CHECK `ck_api_error_logs_error_type`, `0010`):

| `error_type` | Meaning | Default `retryable` |
| --- | --- | --- |
| `NETWORK_TIMEOUT` | Transport timeout or network unavailable | true |
| `HTTP_ERROR` | Non-2xx HTTP or I/O failure without a KIS business code (incl. 5xx) | true |
| `AUTH_ERROR` | Credential missing / rejected (token HTTP 401 / 403), other token HTTP status, market HTTP 401 | false |
| `KIS_BUSINESS_ERROR` | KIS answered `rt_cd != 0` (not rate limit), incl. provider symbol rejection | false |
| `MALFORMED_RESPONSE` | KIS payload structurally invalid or unparseable (incl. numeric mapping failure) | false |
| `MASTER_DOWNLOAD_ERROR` | Instrument master download failure | true |
| `RATE_LIMIT` | KIS `EGW00201`; one row per actual provider attempt | true |
| `LOCAL_INVARIANT` | Local validation / invariant failure (invalid date range, no latest bar, local invalid symbol, instrument not found) | false |
| `UNEXPECTED` | Unrecognised exception at the forward gateway (`Unexpected error (ClassName)`), in the market repository call boundary (`예상치 못한 오류`), or during token issuance (`KIS token request failed: unexpected error`) | false |

`error_type` is derived from the canonical `AppErrorCode` (`KisApiErrorMapper.fromAppErrorCode`); non-transient codes never persist `NETWORK_TIMEOUT`.

`KIS_OAUTH` rows (Phase 11 / Gate 8) derive `error_type` and `retryable` from `KisAuthErrorKind`: 401 / 403 and other HTTP statuses → `AUTH_ERROR`, false; 5xx → `HTTP_ERROR`, true; timeout and network unavailable → `NETWORK_TIMEOUT`, true; malformed token response → `MALFORMED_RESPONSE`, false; unexpected → `UNEXPECTED`, false. Missing credentials send no request and write no `KIS_OAUTH` row. The message is a fixed text; `Throwable.message` is never stored (`docs/150` 20.10.1).

## One row per failure

One external KIS failure attempt produces exactly one row. `KisMarketRepositoryImpl` records provider/transport failures; `KisForwardMarketDataGateway` does not append a second row when it translates an already-recorded `KisMarketException` into a forward result. The gateway records only failures that originate at or above it: local `INVALID_SYMBOL` / `INVALID_DATE_RANGE` (raised before the request), `MAPPING_FAILURE` (raised after the response), local sync invariants, and unrecognised exceptions. `CancellationException` is rethrown and never recorded.

A failed token request inside a market call is recorded once as `KIS_OAUTH` by `KisAuthRepository`; the market repository does not add a second row under the market operation. Only a market call that finds no stored credentials (no request sent) writes one `AUTH_ERROR` row under the market operation (`docs/150` 20.10.2).

## KIS error body

For non-2xx KIS market-data responses the JSON body (`rt_cd`, `msg_cd`, `msg1`, max 4 KB) is parsed when possible. `msg_cd` is stored in `business_code`; `msg1` (max 200 chars) is appended to the public message in `safe_message` and still passes through `sanitize`. Unparseable bodies keep the generic `HTTP_ERROR` with a null `business_code`.

`EGW00201` (rate limit) is logged as `RATE_LIMIT`, retryable, with the HTTP status preserved. Rows written before `0010` keep `KIS_BUSINESS_ERROR`; historical rows are never rewritten.

## Hooks

KIS OAuth, current/daily price, forward sync / historical sync, instrument master download.

## UI

Database Info → Recent API Errors (last 7 days).
