# KIS Read-Only Market Data

Phase 3-B adds a network foundation for KIS domestic-stock quotations.

This phase does not persist market data to Room, does not create instrument rows, and does not call trading or account APIs.

Status:

```text
BUILD / UNIT VERIFIED
RUNTIME DEFERRED — physical device pending
```

## Read-Only Scope

Allowed path prefix:

```text
/uapi/domestic-stock/v1/quotations/
```

Blocked path marker:

```text
/trading/
```

`KisReadOnlyGuard` and `KisReadOnlyInterceptor` reject trading paths with:

```text
IllegalStateException
KIS trading endpoint is prohibited in current BJStock phase
```

This is a development architecture guard, not a live-order safety system. Broker order APIs are still absent.

OAuth `/oauth2/tokenP` remains allowed because it is not a `/uapi/` trading call.

## Current Price Endpoint

```text
GET /uapi/domestic-stock/v1/quotations/inquire-price
TR ID: FHKST01010100
```

Query:

```text
FID_COND_MRKT_DIV_CODE = J
FID_INPUT_ISCD = 6-digit symbol
```

MVP market division is KRX `J` only. NXT / UN are not implemented.

Domain model: `CurrentStockQuote`.

Mapped fields:

| Domain | KIS |
| --- | --- |
| currentPrice | output.stck_prpr |
| previousCloseDifference | output.prdy_vrss |
| changeRate | output.prdy_ctrt |
| openPrice | output.stck_oprc |
| highPrice | output.stck_hgpr |
| lowPrice | output.stck_lwpr |
| volume | output.acml_vol |
| tradingValue | output.acml_tr_pbmn (optional) |
| businessDate | output.stck_bsop_date (optional) |
| source | `KIS` |

`inquire-price` does not always include a business date. Missing date is stored as null. It is not replaced with today.

## Daily Price Endpoint

```text
GET /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice
TR ID: FHKST03010100
```

Query:

```text
FID_COND_MRKT_DIV_CODE
FID_INPUT_ISCD
FID_INPUT_DATE_1
FID_INPUT_DATE_2
FID_PERIOD_DIV_CODE
FID_ORG_ADJ_PRC
```

MVP period is `D` only. W/M/Y are not implemented.

Domain model: `DailyStockBar`.

Mapped fields:

| Domain | KIS output2 |
| --- | --- |
| tradeDate | stck_bsop_date |
| openPrice | stck_oprc |
| highPrice | stck_hgpr |
| lowPrice | stck_lwpr |
| closePrice | stck_clpr |
| volume | acml_vol |
| tradingValue | acml_tr_pbmn (optional) |

One official API call returns the list for the requested range. Automatic pagination / year backfill is Phase 3-D.

## FID_ORG_ADJ_PRC

Wire codes are not scattered. They live in `KisMarketApiConfig`.

Official KIS sample
`examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py`
documents:

```text
0 = 수정주가 (ADJUSTED)
1 = 원주가 (UNADJUSTED)
```

The same sample's example call passes `"1"`. That is an argument choice, not a conflicting mapping. The docstring and validation text both state `0:수정주가 1:원주가`.

Domain enum:

```text
KisPriceAdjustment.ADJUSTED   → 0
KisPriceAdjustment.UNADJUSTED → 1
```

Default for BJStock paper-trading work is `ADJUSTED`.

## Header Policy

Market calls reuse Phase 3-A `getValidToken()`. Headers are built centrally:

```text
authorization: Bearer <token>
appkey
appsecret
tr_id
custtype: P
```

Header names follow the official `kis_auth.py` sample (`appkey` / `appsecret`, lowercase).

Never logged:

- Authorization header
- Access token
- App Key / App Secret
- Full request headers
- Secret-containing bodies

Allowed logs:

```text
KIS inquire-price request started
KIS request success
KIS business error: <msg_cd>
```

## DTO / Domain Mapping

Network DTO names stay on KIS wire keys. Domain names are BJStock names.

Prices, volume, and trading value are `Long` won. Invalid required money strings (`""`, `N/A`, `ABC`) fail mapping. They are not silently replaced with 0. A present `"0"` is a valid zero.

`prdy_ctrt` is a percent string such as `-1.25`. Domain stores it as a ratio scaled by `NumericMapping.RATIO_FACTOR` (8 decimal places). `-1.25%` → `-1_250_000`.

Date query values use `LocalDate` in the repository API and `YYYYMMDD` on the wire.

Daily bars are:

1. Deduplicated by `symbol + tradeDate`, last successful mapping wins
2. Sorted `tradeDate` ascending (oldest → newest)

KIS response order is ignored.

## Error Handling

HTTP 200 is not business success. `rt_cd` must be `"0"`.

| Kind | Typical cause | UI message |
| --- | --- | --- |
| AUTHENTICATION | missing credentials, HTTP 401 | 인증 필요 |
| HTTP | HTTP 500 / other IO | 연결 실패 |
| BUSINESS | HTTP 200 and `rt_cd != 0` | KIS 응답 오류 |
| NETWORK_TIMEOUT | socket / call timeout | 연결 실패 |
| MALFORMED_RESPONSE | invalid JSON | KIS 응답 오류 |
| INVALID_SYMBOL | not 6-digit numeric | 잘못된 종목 |
| INVALID_DATE_RANGE | start > end, or start after today (Asia/Seoul) | 잘못된 조회 기간 |
| MAPPING_FAILURE | required numeric parse failed | KIS 응답 오류 |

`msg_cd` / `msg1` may be kept on an audit object. They are not shown as raw UI copy. Secrets are never copied into errors.

MVP symbol validation is 6-digit numeric (`005930` valid, `5930` / `ABC123` invalid). ETF/ETN `Q` prefixes are not accepted yet and are not permanently closed.

## Read-Only Guard

Unit tests pass `/quotations/inquire-price` and reject `/trading/order-cash`.

## No Room Persistence

Phase 3-B stops at Network → Domain → UI.

`market_daily_bars` is not written. `instruments` rows are not created from a quote lookup.

Wrong mapping must not accumulate a false history. Persistence is Phase 3-C.

## Runtime Deferred

Actual KIS current-price and daily calls, Keystore token issuance, and Settings/Market UI on a physical device remain:

```text
DEFERRED — physical device pending
```

They will be verified together with Phase 2.1 and Phase 3-A when a device is connected.
