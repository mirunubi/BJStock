# Market Data Persistence

Phase 3-C stores KIS read-only daily bars in Room.

Current quotes from Phase 3-B remain UI-only. They are not persisted.

Status:

```text
BUILD / UNIT VERIFIED
RUNTIME DEFERRED — physical device pending
```

## Persistence Scope

Stored:

```text
DailyStockBar → market_daily_bars
```

Not stored:

- CurrentStockQuote snapshots
- WebSocket ticks
- Factors, scores, orders, account data

Network and persistence stay separate:

```text
KisMarketRepository
      │
      ▼
DailyStockBar
      │
      ▼
MarketDataLocalRepository
      │
      ▼
Room
```

`FetchAndPersistDailyBarsUseCase` checks the instrument, requests adjusted daily bars, then writes them in one Room transaction.

## Instrument FK Policy

`market_daily_bars.instrument_id` references `instruments.id`.

Lookup key:

```text
market = KRX
symbol = 005930
```

If that row does not exist, persistence returns `InstrumentNotFound` / `Instrument not registered` and writes zero bars.

## No Auto Instrument Creation

A successful quote or daily fetch does not create an `instruments` row.

Placeholder names such as the symbol itself or `UNKNOWN` are not stored.

Unit tests insert an explicit in-memory instrument, for example `KRX / 005930 / 삼성전자`. Production Room is not seeded.

Instrument master download (KOSPI / KOSDAQ files from the official KIS repository) is Phase 3-D.

## UPSERT Policy

Unique key remains:

```text
(instrument_id, trade_date)
```

Re-collection of the same day updates OHLC / volume / trading value. Duplicate days do not create a second row.

Implementation is `SELECT` + `INSERT` or `UPDATE` inside `database.withTransaction`. `INSERT OR REPLACE` is not used, so the existing `id` is kept.

Same-batch duplicate `tradeDate` values keep the last mapped bar, matching Phase 3-B.

## Adjusted Price Policy

Stored history is adjusted only.

The use case always calls `KisPriceAdjustment.ADJUSTED`. `MarketDataLocalRepository` rejects `UNADJUSTED`. Persistence code does not write KIS `"0"` / `"1"` literals; it reuses Phase 3-B `KisPriceAdjustment`.

## created_at / collected_at

```text
created_at   = first time this Room row was inserted
collected_at = time BJStock received the latest KIS payload (UTC Instant)
```

UPSERT keeps `id` and `created_at`, and refreshes `collected_at`.

`collected_at` is not a KIS trade timestamp.

## Transaction Policy

One API batch is one Room transaction.

OHLC validation runs before writes:

```text
prices and volume >= 0
tradingValue >= 0 when present
high >= low, open, close
low <= open, close
```

Any invalid bar fails the whole batch. Partial inserts are not committed.

Empty KIS success (`0` bars) is `SUCCESS_EMPTY` and does not change the database.

## Query Ordering

Date-range queries return `trade_date ASC` (oldest → newest).

`findLatest(instrumentId)` returns `MAX(trade_date)`.

`countByInstrument(instrumentId)` supports later incremental sync.

Source stored on every KIS daily row:

```text
MarketDataSource.KIS = "KIS"
```

## Runtime Deferred

Actual KIS daily fetch into the on-device Room file remains:

```text
DEFERRED — physical device pending
```

In-memory Room unit tests cover insert, duplicate, update, id stability, missing instrument, rollback, range, latest, and count.
