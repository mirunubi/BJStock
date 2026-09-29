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

## Operation correlation

Room v8 adds nullable `operation_id` (soft reference to `forward_operations.id`). Existing rows stay NULL. Not yet populated at runtime.

## Secrets

Never store AppKey, AppSecret, AccessToken, Authorization headers, account numbers, or raw request/response bodies. `ApiErrorLogService.sanitize` redacts secret-like messages.

## Providers / types

Provider: `KIS`

Types: `NETWORK_TIMEOUT`, `HTTP_ERROR`, `AUTH_ERROR`, `KIS_BUSINESS_ERROR`, `MALFORMED_RESPONSE`, `MASTER_DOWNLOAD_ERROR`

## KIS error body

For non-2xx KIS market-data responses the JSON body (`rt_cd`, `msg_cd`, `msg1`, max 4 KB) is parsed when possible. `msg_cd` is stored in `business_code`; `msg1` (max 200 chars) is appended to the public message in `safe_message` and still passes through `sanitize`. Unparseable bodies keep the generic `HTTP_ERROR` with a null `business_code`.

`EGW00201` (rate limit) is logged as `KIS_BUSINESS_ERROR`, retryable, with the HTTP status preserved.

## Hooks

KIS OAuth, current/daily price, forward sync / historical sync, instrument master download.

## UI

Database Info → Recent API Errors (last 7 days).
