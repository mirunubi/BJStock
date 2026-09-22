# Themes and Watchlists

Phase 9.1 interest baskets built on the existing KOSPI/KOSDAQ local master.

## Source of Truth

`instruments` remains the domestic instrument master. Themes do **not** create a duplicate master.

## Tables

- `themes` — name (unique after trim), description, is_active, timestamps
- `theme_instruments` — many-to-many `(theme_id, instrument_id)` unique

One instrument may belong to many themes. Theme membership duplicates are rejected.

## Theme vs Forward Test Universe

Theme is a live user watchlist.

`strategy_run_instruments` is a Forward Test Universe **snapshot**.

```text
Theme → (user Add From Theme) → copy into strategy_run_instruments
```

Running / READY runs do **not** live-link to themes. Later theme edits never mutate an existing run universe.

Add From Theme is allowed only while the run is `DRAFT`.

## UI

Themes screen: create / rename / deactivate, search `InstrumentDao.searchActive`, add/remove members.

Forward Test DRAFT universe: Add Individual Instrument + Add From Theme.
