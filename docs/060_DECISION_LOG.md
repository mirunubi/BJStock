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
