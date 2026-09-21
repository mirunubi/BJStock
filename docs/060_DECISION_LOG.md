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
