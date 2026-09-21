# BJStock Project Overview

## Purpose

BJStock is a personal Android application for paper trading and investment strategy validation.

The app is installed directly as an APK on a personal Android phone. It is a serverless local application. There is no production backend that the APK depends on for trading.

## Product Scope

| Item | Status |
| --- | --- |
| Personal Android install | Yes |
| Direct APK install | Yes |
| Serverless local app | Yes |
| Paper trading | MVP |
| Live buy / sell | No (future phase) |
| Real broker orders | No (MVP) |
| Virtual account | Yes, implemented by BJStock |
| Factor + weight + score | Yes |
| AI advisory | Optional |
| Forward test duration | 1 year or longer |

## MVP Goal

The first MVP exists to run paper trading and validate strategy quality over a long forward-test period.

What MVP includes:

- Paper trading
- Virtual account
- Factor scoring
- Strategy weights
- Performance tracking

What MVP excludes:

- Real securities orders
- Real buy / sell execution
- Live trading

## Market Data

Korea Investment & Securities (KIS) Open API is the market data source.

KIS is used for market data first. It is not used to place live orders in the MVP.

## Virtual Account

The virtual account is owned and managed by BJStock.

Broker cash, real brokerage balances, and live order routing are out of scope for the MVP.

## Strategy Model

Strategy evaluation uses:

1. Factors
2. Weights
3. Scores

The scoring result drives paper-trading decisions. AI does not replace this path.

## AI Advisory

AI advisory is optional.

It may explain or comment on a decision. It must not execute trades.

## Live Trading

Live trading is a future phase. It is not part of the current foundation or MVP.
