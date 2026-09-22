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

## Secrets

Never store AppKey, AppSecret, AccessToken, Authorization headers, account numbers, or raw request/response bodies. `ApiErrorLogService.sanitize` redacts secret-like messages.

## Providers / types

Provider: `KIS`

Types: `NETWORK_TIMEOUT`, `HTTP_ERROR`, `AUTH_ERROR`, `KIS_BUSINESS_ERROR`, `MALFORMED_RESPONSE`, `MASTER_DOWNLOAD_ERROR`

## Hooks

KIS OAuth, current/daily price, forward sync / historical sync, instrument master download.

## UI

Database Info → Recent API Errors (last 7 days).
