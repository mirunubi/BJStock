# Historical Daily Sync

Phase 3-D backfills `market_daily_bars` for one explicitly selected instrument.

Whole-market collection (every KOSPI/KOSDAQ symbol × a year) is forbidden.

## Flow

```text
Instrument exists
       ↓
Validate startDate <= endDate
       ↓
Split into 90 calendar-day inclusive chunks
       ↓
Sequential KIS daily-bar reads (ADJUSTED)
       ↓
Merge, deduplicate by symbol+tradeDate, sort ASC
       ↓
Validate OHLC
       ↓
One Room transaction
```

If any chunk fails, nothing from that requested range is written.

## Chunk size

KIS daily history can silently truncate around 100 rows per call. 90 calendar days cannot contain 100 trading days, so chunks stay under that limit.

The next chunk starts at `previousEnd + 1 day`. Chunks do not overlap and do not skip dates.

Calls are sequential. There is no parallel fan-out and no hardcoded KIS rate-limit number.

This phase has no WorkManager, alarm, scheduler, or retry loop. Timeout / HTTP / KIS business errors fail the sync.

## Incremental sync

`SyncDailyBarsFromLatestUseCase` reads `findLatest(instrument)`.

- latest exists and `latest < endDate` → start at `latest + 1 day`
- latest >= endDate → `NO_OP` (no API, no writes)
- latest is missing → fail. Do not invent a one-year start date.

## Price basis

`FID_ORG_ADJ_PRC = 0` / `KisPriceAdjustment.ADJUSTED` only. Unadjusted prices are not stored.

## Persistence

Reuses Phase 3-C UPSERT: insert or update by `(instrument_id, trade_date)`, keep row ids, refresh `collected_at`, keep `created_at`. Source is `KIS`.
