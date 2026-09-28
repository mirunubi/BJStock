# Instrument Master

Phase 3-D loads KOSPI and KOSDAQ instrument metadata from official KIS MST zip files.

## Market vs board

KIS domestic quotations use market division `J` = KRX.

BJStock stores:

```text
market = KRX
board  = KOSPI | KOSDAQ | OTHER
symbol = 6-digit listed code, e.g. 005930
```

Samsung Electronics is `KRX` / `KOSPI` / `005930`. Moving from KOSDAQ to KOSPI updates `board` and keeps the same instrument id.

## Sources

URLs live only in `InstrumentMasterConfig`:

```text
https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip
https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip
```

Download uses a dedicated OkHttp client. It does not send a KIS OAuth token and is not mixed with the read-only market REST interceptor.

ZIP bytes are unzipped in memory. No external storage permission is requested.

## Parsing

MST files are CP949/MS949 fixed-width records. Parsing uses byte offsets, not UTF-8 string indexes.

Core part1 window (official sample):

```text
0..8    단축코드
9..20   표준코드
21..    한글 종목명 (remainder of part1)
```

Tail lengths from the official sample:

```text
KOSPI  228 bytes
KOSDAQ 222 bytes
```

Flag offsets walk the official `field_specs` widths. KOSPI `ETP`/`SPAC`/`우선주` are Y/N flags. KOSDAQ `기업인수목적회사여부` is treated as Y/N. KOSDAQ `ETP 상품구분코드` and `우선주 구분 코드` are codes, not documented booleans; only `Y` is treated as a positive match. Any other non-empty code stays `OTHER`.

MVP import accepts 6-digit numeric symbols only. Other code schemes are not guessed.

Each source line is classified as one of:

```text
TARGET_VALID         6-digit numeric symbol with a name; persisted
SKIPPED_UNSUPPORTED  well-formed row outside the MVP universe; not persisted
MALFORMED            truncated or unparseable row
```

A row is `SKIPPED_UNSUPPORTED` when its short code matches `[0-9A-Z]{6,9}`, its standard code matches `[A-Z]{2}[0-9A-Z]{10}`, and its name is present. This covers ETN (`Q500067`), new alphanumeric codes (`0000D0`), fund/REIT (`F70100030`), warrant (`J0036221D`), and K-suffix preferred (`00088K`) rows. The real KOSPI master carries roughly 800 such rows out of about 2,600.

## Completeness

A board sync fails, and existing rows are left unchanged, when:

- first sync parses fewer than 100 target rows
- later syncs parse under 80% of that board's current active count
- the malformed-line ratio exceeds 20%

Skipped-unsupported rows count toward neither the target minimums nor the malformed ratio.

## Persistence

Identity is `(market, symbol)` with `market=KRX`.

On a complete successful board sync:

- existing rows keep `id` and `symbol`
- name, standard_code, board, instrument_type update
- listed_date updates only when the master date parses
- `is_active` becomes true
- symbols missing from this board's master become `is_active=false`
- rows are never DELETED
- `delisted_date` is not set to today

KOSPI and KOSDAQ each run in one Room transaction. Results are reported per board.

## Search

Local SQL `symbol LIKE` or `name LIKE`. Active rows only. No FTS in this phase.
