# 161 Intraday Provider + Platform Feasibility

- Document Status: APPROVED — Human 12-B1 Feasibility Research Approved
- Human Approval Date: 2026-10-02
- Gate: Phase 12-B1
- Architecture Baseline: 5d709b0
- Canonical Architecture: [docs/160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md)
- ImplementationAuthority:
  - 12-B1: CLOSED
  - 12-B2 disposable feasibility probe: HUMAN APPROVED (§21.1, A-1)
  - 12-B2 runtime work: NOT STARTED IN THIS DOCUMENT
- Research Date: 2026-10-02

This document is research and documentation. The Human approved its research evidence and feasibility framing on 2026-10-02 and approved the group-A probe decisions A-1 to A-11 (§21.1). It selects no production provider, purchases nothing, and sets no production numeric value. Every product value that the approved architecture leaves OPEN stays OPEN here.

12-B1 approval does **not** mean that Option A is proven, that an Android-local production runtime is approved, that `specialUse` is the final production foreground-service type, that the production universe cap is known, that final grace / deadline / `max_age` values are known, or that the final Android-vs-external topology is decided. Those remain measure-first (group B) or can-wait (group C) items (§21).

## 0. Executive Summary

Evidence labels used throughout:

| Label | Meaning |
| --- | --- |
| CURRENT REPOSITORY VERIFIED | Read in the BJStock repository at `5d709b0` (read-only) |
| OFFICIAL EXTERNAL VERIFIED | Stated in a primary / official source listed in §23, accessed 2026-10-02 |
| PARTIALLY VERIFIED | Official source exists but is incomplete, ambiguous, sample-code-only, or its content could not be fully extracted |
| UNVERIFIED | No official evidence found during this research |
| PROPOSED | Research suggestion only; not a decision |
| OPEN | Requires a Human decision or later-gate evidence |
| DEFERRED | Explicitly out of the current scope |

Key findings:

1. **Korean stock intraday (KIS):** OFFICIAL EXTERNAL VERIFIED that KIS publishes 1-minute bar endpoints (today: 30 rows per call; past days: 120 rows per call, with a stated retention ceiling of up to 1 year, "최대 1년 분봉 보관"; actual available depth UNVERIFIED) and a WebSocket trade stream (`H0STCNT0`) with a second-resolution trade time. No native 15-minute bar is documented for Korean stocks, so 15M bars would be BJStock aggregation of 1-minute bars or trades. [S-03] [S-04] [S-06]
2. **Option A (first verifiable price strictly after `order_created_at`):** classified **POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE**. Option A is acceptable only when BJStock can prove `execution_price_time > order_created_at`; if that cannot be proven, the result is **NO FILL** (no approximation, no backfill). Four evidence domains are involved (§14.1): the device wall clock (`order_created_at`), the KIS / exchange HHMMSS trade time, provider transport / arrival time, and stream ordering / batching / reconnect state. The device clock may jump, the device–exchange offset is not bounded, HHMMSS truncation vs rounding is undocumented, `H0STCNT0` has no documented sequence field, and reconnects may create silent gaps. A trade stamped in the same second as the order can never prove strict-after (§14.2). A safe rule must be derived from 12-B2 evidence; no margin is set here. A switch to Option B requires a later explicit Human approval.
3. **Korean futures (KIS):** OFFICIAL EXTERNAL VERIFIED 30-second / 1-minute futures bars (up to 102 rows per call, documented for real accounts) and an index-futures real-time stream (`H0IFCNT0`) that is **real-account only (모의투자 미지원)**. The commodity-futures stream `H0CFCNT0` does not state its environment support (UNVERIFIED); the `H0IFCNT0` restriction is not generalized to other futures streams. Volume and open interest fields exist in the stream. A continuous / roll series is not documented. Futures are excluded from the initial 12-B2 probe (§21.1, A-7). [S-13] [S-15] [S-16]
4. **USD/KRW:** no verified intraday spot source. KRX U.S. dollar futures contracts are listed in the public KIS master file, so a **futures proxy** (never spot) remains conceptually possible, but **KIS market-data access for currency futures through the researched endpoints is UNVERIFIED**: the commodity-futures stream `H0CFCNT0` does not name currency futures (its example code is `101S12`), and the documented REST futures market codes are `F` / `O` only. Daily reference rates exist (Seoul Money Brokerage; Bank of Korea ECOS) but could only be PARTIALLY verified. [S-12] [S-13] [S-16] [S-18] [S-33] [S-34]
5. **U.S. Treasury:** OFFICIAL EXTERNAL VERIFIED that the official par yield curve is a **daily** series built from indicative bid-side quotes at about 3:30 PM ET and usually published by 6:00 PM ET. It is suitable only as `DAILY_CONTEXT`. Treasury publishes no intraday yields. [S-31] [S-32]
6. **Overseas futures / index:** KIS documents overseas-futures minute bars (sample shows 1 / 5 / 10 / 15 / 30 / 60-minute grouping) and a real-time stream, but **CME and SGX real-time data requires a paid subscription** (price not published in the reviewed sources). Timestamp time zone and DST semantics: UNVERIFIED. [S-19] [S-20]
7. **Breadth / Theme:** KIS domestic-index real-time stream (`H0UPCNT0`) carries advancing / declining / unchanged / limit-up / limit-down issue-count fields, so breadth may be provider-supplied (PARTIALLY VERIFIED; field semantics must be probed). Theme aggregation is internal derivation from member bars; no external provider is required. [S-11]
8. **Rate limits:** no official numeric KIS REST or WebSocket limit was found. Officially documented: the `EGW00201` "초당 거래건수 초과" error exists and demo accounts have lower limits (no number). The KIS sample code paces 0.05 s (production) / 0.5 s (demo); these are sample choices, not published limits. The sample also raises "Subscription's max is 40" when `len(open_map.keys()) > 40`, but its keys are distinct subscribed function / TR-type names, each holding a list of ticker items, so **40 is proven neither as an instrument-count limit nor as an official KIS session / account limit**. The official per-session instrument-registration limit is **UNVERIFIED**; WebSocket capacity for 10 / 30 / 50 / 100 instruments must be measured or officially confirmed (§13). REST polling demand remains calculable from the polling design. [S-01] [S-02]
9. **Android (targetSdk 36):** **FLAG — `dataSync` (and `mediaProcessing`) foreground services are limited to 6 hours per 24 hours while the app is in the background, which is shorter than the 6.5-hour KRX regular session (09:00–15:30).** The limit resets only when the user brings the app to the foreground. `specialUse` has no documented time limit but requires a declared subtype and, if distributed through Google Play, Play Console review. Doze suspends network and ignores wake locks; whether an active foreground service keeps network access during Doze is not stated in the reviewed docs and must be measured (a foreground service by itself is not documented to guarantee network or CPU). No production foreground-service type is selected; the Human approved `specialUse` as the initial 12-B2 probe candidate only (§21.1, A-2). Android 17 adds a RAM-based memory limiter as a possible process-exit cause (§15.3). [S-35] [S-36] [S-42] [S-52]
10. **KRX session:** regular session 09:00–15:30 with a closing call auction 15:20–15:30 (KIS official guide); KRX official pages confirm after-hours closing-price trading 15:40–16:00 and an after-market 16:00–20:00. A holiday API (KIS `chk-holiday`) and an official KRX holiday page exist. Minute-bar labelling (bar start vs end) and auction-print placement are UNVERIFIED and are 12-B2 measurement items. [S-07] [S-27] [S-28] [S-30]

Gate status (§22, §24): **Phase 12-B1 APPROVED / CLOSED (Human, 2026-10-02).** The Independent Delta Re-verification passed (0 BLOCKER, 0 HIGH, 0 MEDIUM). The Human approved the group-A decisions A-1 to A-11 (§21.1), including authority for a disposable, isolated 12-B2 feasibility probe (docs/160 §27.1). 12-B2 is NOT STARTED by this document and requires a separate execution instruction. Group-B values are measured first; group-C items can wait until after 12-B2. 12-C is NOT AUTHORIZED.

## 1. Authority / Scope

- Executor role: Provider / Platform Feasibility Researcher. Research and documentation only.
- ImplementationAuthority during 12-B1 research: NONE. No Kotlin, Room, SQL / migration, AndroidManifest, scheduler, provider integration, runtime probe, device test, or strategy work was performed. After approval, 12-B1 is CLOSED and the 12-B2 disposable probe is HUMAN APPROVED (§21.1, A-1) but not started by this document.
- No authenticated provider call was made. No KIS `/trading/` path was touched. No secret, token, account identifier, or credential preference was read or printed. No subscription was purchased. No provider account was logged into.
- External evidence was gathered from public, unauthenticated web pages and the public KIS sample repository only.
- Canonical architecture: [docs/160](160_INTRADAY_MULTI_FACTOR_MODEL_ARCHITECTURE.md) (HUMAN APPROVED 2026-10-02). This document does not modify or reinterpret docs/160; where a finding interacts with an approved decision, the decision stands and the finding is listed as OPEN input for a later gate.
- PAPER TRADING ONLY. No real brokerage path is researched for use.

## 2. Current BJStock Data Baseline

All rows CURRENT REPOSITORY VERIFIED (read-only inspection at `5d709b0`).

| Area | Current state | Evidence |
| --- | --- | --- |
| KIS base URLs | PRODUCTION `https://openapi.koreainvestment.com:9443`; VIRTUAL `https://openapivts.koreainvestment.com:29443`; token path `/oauth2/tokenP`, `client_credentials`, 5-minute token safety margin | [`KisEnvironmentConfig.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/KisEnvironmentConfig.kt) |
| KIS endpoints in use | `/uapi/domestic-stock/v1/quotations/inquire-price` (`FHKST01010100`) and `inquire-daily-itemchartprice` (`FHKST03010100`) only; market division `J` (KRX) only; period `D` only | [`KisMarketApiConfig.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/market/KisMarketApiConfig.kt), [`KisMarketApi.kt`](../app/src/main/java/com/mirunubi/bjstock/core/network/kis/KisMarketApi.kt), [`KisModels.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/KisModels.kt) |
| Mapped fields | Envelope `rt_cd`, `msg_cd`, `msg1`. Current price (`output`): `stck_prpr`, `prdy_vrss`, `prdy_ctrt`, `stck_oprc`, `stck_hgpr`, `stck_lwpr`, `acml_vol`, `acml_tr_pbmn`, `stck_bsop_date`. Daily bars (`output2`): `stck_bsop_date`, `stck_oprc`, `stck_hgpr`, `stck_lwpr`, `stck_clpr`, `acml_vol`, `acml_tr_pbmn`. No intraday time field is mapped | [`KisMarketDtos.kt`](../app/src/main/java/com/mirunubi/bjstock/core/network/kis/KisMarketDtos.kt) |
| Pacing / retry | PRODUCTION 100 ms, VIRTUAL 500 ms minimum interval; only `EGW00201` retried, 61,000 ms wait, at most 3 attempts | [`KisRequestPolicy.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/market/KisRequestPolicy.kt), [`SyncHistoricalDailyBarsUseCase.kt`](../app/src/main/java/com/mirunubi/bjstock/core/marketdata/SyncHistoricalDailyBarsUseCase.kt) |
| Read-only guard | Substring deny rule on the lower-cased path: rejects any path containing `/trading/`, and any `/uapi/` path without `/quotations/`; applied to `request.url.encodedPath` by an OkHttp interceptor on the single KIS client (token and market APIs). It is **not a positive allowlist** and does not check the host or TR ID | [`KisReadOnlyGuard.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/market/KisReadOnlyGuard.kt), [`KisReadOnlyInterceptor.kt`](../app/src/main/java/com/mirunubi/bjstock/core/network/kis/KisReadOnlyInterceptor.kt) |
| Secret custody | `SharedPreferences` containing AndroidKeyStore AES-GCM-encrypted payloads (app key, app secret, access token) | [`EncryptedKisSecretStore.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/EncryptedKisSecretStore.kt), [`AesGcmSecretCipher.kt`](../app/src/main/java/com/mirunubi/bjstock/core/security/AesGcmSecretCipher.kt) |
| Token caching | `getValidToken` runs under a mutex, reuses the stored token while it is usable (expiry minus the 5-minute margin), and otherwise issues and stores a new one | [`KisAuthRepository.kt`](../app/src/main/java/com/mirunubi/bjstock/core/kis/KisAuthRepository.kt) |
| Scheduling | DAILY Auto uses one-time WorkManager requests per slot | [`ForwardTestScheduler.kt`](../app/src/main/java/com/mirunubi/bjstock/core/forward/ForwardTestScheduler.kt) |
| Platform | `compileSdk 37`, `targetSdk 36`, `minSdk 28`; manifest declares only `INTERNET` (no foreground service, no `FOREGROUND_SERVICE*`, no `POST_NOTIFICATIONS`, no `WAKE_LOCK`, no exact-alarm permission) | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` |
| Absent | No WebSocket client, minute bars, futures, FX, Treasury, overseas, breadth, or calendar code | Repository search |

## 3. Approved Requirements From docs/160

Requirements this research must respect (all HUMAN APPROVED in docs/160):

| Requirement | docs/160 reference |
| --- | --- |
| Time ordering `slot_at ≤ decision_cutoff_at ≤ decision_at ≤ order_created_at ≤ decision_deadline_at < next_slot_at` | §11, §36 #4 |
| Option A preferred: first provider-verifiable eligible price strictly after `order_created_at`; Option B only by a later explicit Human decision; next-bar open is offline research only | §21, §36 #3 |
| Strict execution lower bound applies to INTRADAY only, not DAILY | §21 |
| Pending-exposure invariant; additional buy DISALLOW; same-day re-entry after full closure | §20.1, §36 #6 |
| Source-specific `max_age`; MISSING never zero; Fail-Closed; audit reason retained | §0.2, §13, §36 #10 |
| No retroactive trade; missed slots never reconstructed | §29, §30, §36 #12 |
| KRX regular session only; official / verified calendar required | §31, §36 #5 |
| Multi-provider, KIS-first; no provider approved by candidacy; no KIS `/trading/` | §14, §36 #9 |
| Raw / native verified price basis, never mixed within one Intraday Run | §36 #16 |
| `DAILY_CONTEXT` inputs allowed with explicit timeframe identity | §16, §36 #17 |
| Small explicit universe (tens of instruments); replay-safe retention | §33, §36 #13 |
| Android foreground tested first: 12-B1 → 12-B2 → Human topology decision → 12-C | §27, §27.1, §36 #11 |
| Carry-forward findings F-21 to F-24 | §37 |

## 4. Research Method / Evidence Standard

- Primary / official sources only: the KIS-maintained `koreainvestment/open-trading-api` repository (sample code and docstrings authored by KIS), the KIS API portal and KIS customer guide, KRX official sites, U.S. Treasury, Bank of Korea, Seoul Money Brokerage, developer.android.com, and Google Play Console Help.
- KIS sample code is treated as OFFICIAL EXTERNAL VERIFIED for what its docstrings state (endpoint path, TR ID, parameters, row caps, account support), and as PARTIALLY VERIFIED for behaviour that only the sample's own code enforces (for example its sleep interval and its `len(open_map.keys()) > 40` check, which counts subscribed TR-type keys rather than instruments), because those are sample choices rather than published service limits. No sample-code value is treated as an official KIS limit.
- Third-party material (blogs, Q&A sites, news, community posts) was used only to discover official sources and is never cited as verification evidence. Numeric KIS limits that circulate only in third-party posts (including "41 registrations per session" reports) are reported as UNVERIFIED and are not used.
- Field semantics are inferred from KIS field names only where stated; such inferences are labelled PARTIALLY VERIFIED and listed for 12-B2 confirmation.
- Every external URL and its access date (2026-10-02) is listed in §23. Some official pages are rendered dynamically (KIS portal, KRX holiday table, KRX general trading-rules tabs, Bank of Korea ECOS); where content could not be extracted, the claim is labelled PARTIALLY VERIFIED or UNVERIFIED.
- No product value is selected. Where research suggests an approach, it is labelled PROPOSED.

## 5. Korean Stock Intraday

| Capability | Finding | Label | Source |
| --- | --- | --- | --- |
| Today's 1-minute bars | `GET /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice`, TR `FHKST03010200`; real and demo accounts; max 30 rows per call; today only ("전일자 분봉 미제공"); a future `FID_INPUT_HOUR_1` returns the current value | OFFICIAL EXTERNAL VERIFIED | [S-03] |
| In-progress bar caveat | The first `output2` row's `cntg_vol` shows the previous minute's volume until the first trade of the new minute occurs | OFFICIAL EXTERNAL VERIFIED | [S-03] |
| Past-day 1-minute bars | `inquire-time-dailychartprice`, TR `FHKST03010230`; max 120 rows per call (stated for real accounts); past dates via `FID_INPUT_DATE_1` / `FID_INPUT_HOUR_1`; "최대 1년 분봉 보관"; `FID_FAKE_TICK_INCU_YN` (허봉 inclusion) parameter | OFFICIAL EXTERNAL VERIFIED (demo support UNVERIFIED) | [S-04] |
| Venue selection | Both minute endpoints accept `J` (KRX), `NX` (NXT), `UN` (integrated) | OFFICIAL EXTERNAL VERIFIED | [S-03] [S-04] |
| Native 15M | Not documented for Korean stocks; the KIS sample repository's domestic-stock bar endpoints are 1-minute (today / past day) and daily / periodic only | UNVERIFIED (treat as absent) | [S-03] [S-04] |
| Real-time trades | WebSocket TR `H0STCNT0` (KRX), real and demo use the same TR; columns include `MKSC_SHRN_ISCD`, `STCK_CNTG_HOUR`, `STCK_PRPR`, `STCK_OPRC/HGPR/LWPR`, `CNTG_VOL`, `ACML_VOL`, `BSOP_DATE`, `NEW_MKOP_CLS_CODE`, `TRHT_YN`, `HOUR_CLS_CODE`, `MRKT_TRTM_CLS_CODE`, `VI_STND_PRC`, `MARKET_CLS_CODE` | OFFICIAL EXTERNAL VERIFIED (column list) | [S-06] |
| Trade time semantics | `STCK_CNTG_HOUR` is a six-digit HHMMSS value (second resolution). Exchange-side KST time is implied by the name, not stated. Truncation vs rounding of sub-second time is not documented. No sequence-number column is documented | PARTIALLY VERIFIED | [S-06] |
| Stream frame format | The sample splits each data frame on the pipe character into flag, TR ID, record count, and payload, and parses the payload as `^`-separated fields; the sample does not use the `count` field, so batching and completeness semantics are not documented | PARTIALLY VERIFIED (sample parsing only) | [S-02] |
| NXT / integrated trade streams | `H0NXCNT0` ("국내주식 실시간체결가 (NXT)") and `H0UNCNT0` ("국내주식 실시간체결가 (통합)"), both with `STCK_CNTG_HOUR`. Candidates only; relevant if venue ≠ `J` or if NXT / integrated execution is considered later; demo support and semantics not reviewed | PARTIALLY VERIFIED | [S-56] [S-57] |
| Recent trades (REST) | `/uapi/domestic-stock/v1/quotations/inquire-ccnl`, TR `FHKST01010300` ("주식현재가 체결"), real and demo use the same TR, `J` / `NX` / `UN`; output fields not documented in the sample. Candidate only; relevant if `H0STCNT0` is not authorized or not sufficient | PARTIALLY VERIFIED (output UNVERIFIED) | [S-55] |
| Time-bucketed trades (REST) | `inquire-time-itemconclusion`, TR `FHPST01060000` ("당일시간대별체결"); output fields not documented in the sample | PARTIALLY VERIFIED | [S-05] |
| Market operation info | WebSocket TR `H0STMKO0` ("국내주식 장운영정보 (KRX)") | PARTIALLY VERIFIED (fields not reviewed) | [S-09] |
| Adjusted vs raw | The minute endpoints document no adjusted / unadjusted switch (the daily chart has one). A trade-stream price is a native trade price. Whether minute bars are raw: UNVERIFIED | UNVERIFIED | [S-03] [S-04] |
| History depth | Retention ceiling of up to 1 year of 1-minute bars on KIS servers ("최대 1년 분봉 보관"); actual available depth UNVERIFIED; older history requires another source or BJStock's own retention | OFFICIAL EXTERNAL VERIFIED (ceiling) / UNVERIFIED (actual depth) | [S-04] |
| Cost | No market-data fee for domestic stock quotations was found in the reviewed sources | UNVERIFIED | — |
| Licensing / redistribution | KIS Open API terms were not retrievable as static text (dynamic portal) | UNVERIFIED | [S-26] |

Implications for the approved architecture:

- 15M bars must be **aggregated by BJStock** from confirmed 1-minute bars or trades. Bar-boundary labelling (whether a KIS minute bar is labelled by its start or end minute) is not documented and decides the aggregation; it is a 12-B2 measurement item.
- The 허봉 (fake / no-trade bar) option means a provider may emit bars with no trades. Whether such bars may enter factor inputs is OPEN; they must never be confused with real observations (no synthetic data, §0.2).
- Raw / native basis (§36 #16): the trade stream gives a native trade price. Mixing `J` and `UN` venue codes within one Run would mix bases; the venue code must be fixed per Run (OPEN, §21).
- Retention: because KIS states a retention ceiling of up to 1 year (actual depth UNVERIFIED), replay-safe retention (§36 #13) depends on BJStock storing what it used.
- The alternate trade paths above are PARTIALLY VERIFIED candidates, not approved fallbacks.

Area recommendation: **GO TO 12-B2.** The group-A decisions in §21.1 are Human-approved: Korean domestic stocks are the initial probe scope (venue `J`, instruments `005930` and `000660`, `H0STCNT0` over VIRTUAL credentials). Measure latency, labelling, auction placement, and 허봉 behaviour.

## 6. Korean Futures

| Capability | Finding | Label | Source |
| --- | --- | --- | --- |
| Current price | `/uapi/domestic-futureoption/v1/quotations/inquire-price` (market `F` index futures / `O` index options); real and demo parameter present | OFFICIAL EXTERNAL VERIFIED | [S-12] |
| Minute bars | `/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice`, TR `FHKIF03020200`; `FID_HOUR_CLS_CODE` 30 = 30 s, 60 = 1 min; past data Y/N; 허봉 flag; max 102 rows per call for real accounts; example code `101T12` | OFFICIAL EXTERNAL VERIFIED (demo support UNVERIFIED) | [S-13] |
| Daily bars | `inquire-daily-fuopchartprice`, max 100 rows per call (real and demo) | OFFICIAL EXTERNAL VERIFIED | [S-14] |
| Index-futures real-time | WebSocket `H0IFCNT0`; **real accounts only; 모의투자 미지원**; fields include `bsop_hour`, `futs_prpr`, OHLC, `last_cnqn`, `acml_vol`, `hts_otst_stpl_qty` (open interest), `mrkt_basis`, best bid / ask | OFFICIAL EXTERNAL VERIFIED | [S-15] |
| Commodity-futures real-time | WebSocket `H0CFCNT0` ("상품선물 실시간체결가"). The docstring does not name currency or rate futures, its example `tr_key` is `101S12`, and demo support is not stated | OFFICIAL EXTERNAL VERIFIED (existence) / UNVERIFIED (currency-futures coverage; environment support) | [S-16] |
| Contract master | Public master file `fo_com_code.mst.zip` lists KRX 3 / 5 / 10-year KTB futures and U.S. dollar futures contract months | OFFICIAL EXTERNAL VERIFIED | [S-17] [S-18] |
| Business-day lookup | `/uapi/domestic-stock/v1/quotations/market-time`, TR `HHMCM000002C0` ("국내선물 영업일조회") | PARTIALLY VERIFIED (fields not reviewed) | [S-08] |
| Volume semantics | Per-contract cumulative and per-trade volume in the stream; minute-bar output fields not documented in the sample | PARTIALLY VERIFIED | [S-13] [S-15] |
| Continuous / roll series | Not documented | UNVERIFIED | — |
| Trading hours | KIS guide column "장내파생상품 — 주식상품 (변동성선물제외)": regular session 09:00–15:45 (최종거래일 09:00–15:20), order acceptance 08:20–15:45, 동시호가 08:30–09:00 and 15:35–15:45. The guide's notes state "상품선물의 최종거래일 거래시간은 09:00~11:30" and that some commodity futures may differ. Regular hours for currency / rate futures are not listed in that table | OFFICIAL EXTERNAL VERIFIED (KIS guide, equity-product derivatives) / UNVERIFIED (currency / rate futures regular hours) | [S-27] |

Implications:

- Index-futures (지수선물; sample example code `101T12`) price and volume at 30-second / 1-minute granularity are documented; which index a given code tracks is not stated in the sample; 15M needs aggregation.
- `H0IFCNT0` index-futures real-time is VERIFIED real-account-only (a PRODUCTION app key). `H0CFCNT0` environment support remains UNVERIFIED. The `H0IFCNT0` restriction is not generalized to all futures streams. BJStock supports both environments today; the initial 12-B2 probe uses VIRTUAL credentials and excludes futures (§21.1, A-3, A-7), so this fact does not bear on the probe credential decision.
- A front-month roll rule must be defined by BJStock (engineering / Human, later gate); per-contract volume is not a continuous series.
- Futures trade beyond 15:30, but the MVP evaluates only within the KRX stock regular session (§36 #5).

Area recommendation: futures are **excluded from the initial 12-B2 probe** (§21.1, A-7) and may be added only by later bounded authority. If futures are added later, the credential class must be decided per stream: `H0IFCNT0` index-futures real-time is VERIFIED real-account-only, while `H0CFCNT0` environment support remains UNVERIFIED. The front-month roll rule **can wait** (group C).

## 7. USD/KRW

| Candidate | Class | Finding | Label | Source |
| --- | --- | --- | --- | --- |
| KRX U.S. dollar futures via KIS | Exchange-traded futures (proxy, includes forward basis); **not USD/KRW spot** | Contracts are listed in the public KIS master (VERIFIED). KIS market-data access for these contracts through the researched endpoints is **UNVERIFIED**: `H0CFCNT0` does not name currency futures and its example code is `101S12`; the REST futures price / minute endpoints document market codes `F` (지수선물) and `O` (지수옵션) only | Master listing: OFFICIAL EXTERNAL VERIFIED; data access: UNVERIFIED | [S-12] [S-13] [S-16] [S-18] |
| Seoul Money Brokerage 매매기준율 | Daily official reference rate | Official page reachable; table rendered dynamically, content not extracted | PARTIALLY VERIFIED | [S-34] |
| Bank of Korea ECOS | Daily statistics | Open API page returned an access-denied response from the research network; capability not reviewed | UNVERIFIED | [S-33] |
| Third-party intraday spot FX vendors | Licensed intraday spot | No official vendor documentation reviewed | UNVERIFIED | — |

- No usable intraday USD/KRW source is verified. A KRX U.S. dollar futures proxy remains conceptually possible, but it is a futures price, never spot, and KIS access to it is UNVERIFIED. Using a futures price where the model expects spot would be a semantic choice (OPEN, Human).
- Daily reference rates fit `DAILY_CONTEXT` (§36 #17) once verified.
- Open issues: KIS currency-futures data access (stream and REST); currency-futures session hours; front-month roll; whether a spot source is required.

Area recommendation: the USD/KRW source class **can wait until after 12-B2** (group C, §21). KIS currency-futures access and intraday spot: **NEEDS MORE PROVIDER RESEARCH**.

## 8. U.S. Treasury

| Item | Finding | Label | Source |
| --- | --- | --- | --- |
| Official series | Daily Treasury Par Yield Curve Rates | OFFICIAL EXTERNAL VERIFIED | [S-31] |
| Inputs | "indicative, bid-side market price quotations (not actual transactions) … obtained by the Federal Reserve Bank of New York at or near 3:30 PM each trading day" | OFFICIAL EXTERNAL VERIFIED | [S-32] |
| Publication | "usually available … by 6:00 PM Eastern Time each trading day, but may be delayed" | OFFICIAL EXTERNAL VERIFIED | [S-32] |
| Access | Public web pages and interest-rate XML feeds (developer notice on XML changes exists); no credential | PARTIALLY VERIFIED (feed format not reviewed) | [S-31] |
| Intraday | Treasury publishes no intraday yields | OFFICIAL EXTERNAL VERIFIED (by cadence) | [S-31] [S-32] |

Time conversion (derived, not a source claim): 3:30 PM ET is 04:30 KST next day (EDT) or 05:30 KST (EST); 6:00 PM ET is 07:00 KST (EDT) or 08:00 KST (EST). Before the 09:00 KST open, the previous U.S. trading day's curve is **usually** available, but "may be delayed" means `available_at` must be the observed retrieval time, never an assumed publication time. U.S. and KRX holidays differ, so `effective_at` gaps are expected and must follow `max_age` (OPEN value).

- `DAILY_CONTEXT` suitability: **suitable** (VERIFIED cadence), as in the docs/160 example `US10Y_CONTEXT`.
- Intraday: the official source is **NOT SUITABLE**; a proxy would be U.S. Treasury futures through a market-data provider (for example CME products via KIS overseas futures, which requires a paid real-time subscription, §9) and would be a price, not a yield (UNVERIFIED).

Area recommendation: Treasury is **excluded from the initial 12-B2 probe** (§21.1, A-7); `DAILY_CONTEXT` publication-time observation may be added only by later bounded authority. A paid / intraday proxy **can wait until after 12-B2** (group C).

## 9. Overseas Futures / Index Signals

| Capability | Finding | Label | Source |
| --- | --- | --- | --- |
| Minute bars | `/uapi/overseas-futureoption/v1/quotations/inquire-time-futurechartprice`, TR `HHDFC55020400`; exchange code (`CME`, `EUREX` in examples); `QRY_CNT` 120; `QRY_GAP` examples '1', '5', '10', '15', '30', '60' (minutes) | OFFICIAL EXTERNAL VERIFIED (sample docstring) | [S-19] |
| Native 15M | Implied by `QRY_GAP` '15' in the sample | PARTIALLY VERIFIED | [S-19] |
| Real-time | WebSocket `HDFFF020`; "CME, SGX 실시간시세 유료시세 신청 필수"; after subscribing, a token must be issued and sync takes up to 2 hours | OFFICIAL EXTERNAL VERIFIED | [S-20] |
| Tick history | `tick_ccnl` (QRY_CNT up to 40) | PARTIALLY VERIFIED | [S-21] |
| Session times | `/uapi/overseas-futureoption/v1/quotations/market-time`, TR `OTFM2229R` | PARTIALLY VERIFIED | [S-22] |
| Overseas indices | Overseas index minute chart and an index code list exist in KIS samples | PARTIALLY VERIFIED | [S-24] [S-25] |
| Overseas stock delay | Overseas stock real-time is free for the U.S. (0-minute delay), 15-minute delay for HK / VN / CN / JP unless subscribed | OFFICIAL EXTERNAL VERIFIED (stocks, not futures) | [S-23] |
| Timestamp time zone / DST | Not documented in reviewed samples | UNVERIFIED | — |
| Account requirement | Whether an overseas-futures account is required for these quotations | UNVERIFIED | — |
| Cost | Paid subscription required for CME / SGX real-time; price not published in reviewed sources | PARTIALLY VERIFIED | [S-20] |

Area recommendation: the paid subscription / licensing decision **can wait until after 12-B2** (group C, §21); **NEEDS MORE PROVIDER RESEARCH** (time zone, DST, account requirement, non-real-time delay for REST bars).

## 10. Theme / Breadth Derivation

- **Theme aggregation:** derivable internally from member 1-minute / 15M bars once Korean stock intraday is available (§5). No external provider is required. Acquisition cost scales with theme member count; members outside the Run universe need explicit, auditable inclusion (§36 #13, #14). Weighting is a 12-F item.
- **Market breadth:** KIS domestic-index WebSocket `H0UPCNT0` includes `uplm_issu_cnt`, `ascn_issu_cnt`, `stnr_issu_cnt`, `down_issu_cnt`, `lslm_issu_cnt` (names suggest limit-up / advancing / unchanged / declining / limit-down issue counts), plus `bsop_hour`. PARTIALLY VERIFIED: semantics are inferred from field names and must be confirmed in 12-B2. A REST breadth source was not found. [S-11]
- **Index level:** KIS sector / index minute chart `inquire-time-indexchartprice`, TR `FHKUP03500200`, `FID_INPUT_HOUR_1` examples '30', '60', '600', '3600' (the sample does not state the unit; reading them as seconds is an inference); no '900' example is shown, so a native 15M index interval is not documented. [S-10]
- Deriving breadth from whole-market bars is **NOT FEASIBLE AS CURRENTLY PROPOSED** (would require full KOSPI / KOSDAQ intraday collection, which §36 #13 excludes by default).

Area recommendation: Theme needs no separate acquisition beyond stock bars (covered by §5); Theme aggregation and index breadth are **excluded from the initial 12-B2 probe** (§21.1, A-7) and may be added only by later bounded authority (confirm `H0UPCNT0` semantics; demo support UNVERIFIED).

## 11. Provider Capability Matrix

Status vocabulary: VERIFIED / PARTIALLY VERIFIED / UNVERIFIED / NOT SUITABLE. "Current BJStock Support" is CURRENT REPOSITORY VERIFIED. Cost and licensing cells say UNVERIFIED where no official statement was found.

| Input | Candidate Provider | Official Evidence | Current BJStock Support | Provider Capability | Cadence | Timestamp Semantics | Historical Availability | REST / Stream | Rate/Pacing | Credential Requirement | Cost | Licensing / Redistribution Concern | Retention Concern | 15M Suitability | Option A Suitability | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Korean stock intraday OHLCV | KIS `inquire-time-itemchartprice` / `inquire-time-dailychartprice`; or self-aggregation from `H0STCNT0` | [S-03] [S-04] [S-06] | None (daily `D` + current quote) | 1-minute bars; today 30 rows/call; past 120 rows/call | 1 min; 15M by aggregation | Bar label start/end UNVERIFIED; in-progress first row documented | Retention ceiling up to 1 year at KIS; actual depth UNVERIFIED | Both | No official number; `EGW00201` documented | KIS app key / secret → token | UNVERIFIED | UNVERIFIED (terms not retrievable) | KIS retention ceiling up to 1 year; BJStock must retain replay inputs | Suitable via aggregation (PARTIALLY) | n/a (see next row) | PARTIALLY VERIFIED |
| Korean stock post-order executable price evidence | KIS `H0STCNT0`; candidates (PARTIALLY VERIFIED, not approved fallbacks): REST `inquire-ccnl`, REST `inquire-time-itemconclusion`, `H0NXCNT0` / `H0UNCNT0` | [S-06] [S-05] [S-55] [S-56] [S-57] | None (`inquire-price` has no mapped intraday time) | Per-trade price with HHMMSS trade time | Per trade | Second resolution; KST exchange time implied, not stated; truncation vs rounding undocumented; no documented sequence field; device-clock offset unbounded | Live only (stream) | Stream (REST output fields UNVERIFIED) | WebSocket session capacity UNVERIFIED (the sample `> 40` check counts TR-type keys, not instruments) | WS approval key from app key / secret | UNVERIFIED | UNVERIFIED | Fill evidence must be retained (F-21) | n/a | POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE | PARTIALLY VERIFIED |
| Korean futures price | KIS `inquire-time-fuopchartprice`, `inquire-price`, `H0IFCNT0` | [S-12] [S-13] [S-15] | None | 30 s / 1 min bars (≤ 102 rows/call, real account); real-time stream | 30 s / 1 min | `bsop_hour` HHMMSS (semantics UNVERIFIED) | Past-data flag; depth UNVERIFIED | Both | No official number | Real-time `H0IFCNT0`: real-account key only (VERIFIED); `H0CFCNT0` environment UNVERIFIED | UNVERIFIED | UNVERIFIED | Per-contract; roll needed | Suitable via aggregation (PARTIALLY) | n/a | PARTIALLY VERIFIED |
| Korean futures volume | Same as above | [S-13] [S-15] | None | `acml_vol`, `last_cnqn`, open interest in stream | Per trade / 1 min | As above | As above | Both | As above | As above | UNVERIFIED | UNVERIFIED | Per-contract, no continuous series | Suitable via aggregation (PARTIALLY) | n/a | PARTIALLY VERIFIED |
| USD/KRW | KRX USD futures (proxy, not spot); SMBS daily; BOK ECOS daily; licensed spot vendor | [S-12] [S-13] [S-16] [S-18] [S-34] [S-33] | None | Futures proxy conceptually possible; KIS currency-futures data access UNVERIFIED; daily reference rates | Futures per trade (if accessible); references daily | Futures UNVERIFIED; daily reference publication time UNVERIFIED | UNVERIFIED | KIS currency-futures stream and REST access UNVERIFIED | UNVERIFIED | KIS key (futures); none / API key (BOK, UNVERIFIED) | UNVERIFIED | UNVERIFIED | Long-term | Futures proxy access UNVERIFIED; spot UNVERIFIED | n/a | UNVERIFIED (intraday); PARTIALLY VERIFIED (daily references) |
| U.S. Treasury DAILY_CONTEXT | U.S. Treasury Daily Par Yield Curve | [S-31] [S-32] | None | Daily par yields from ~3:30 PM ET quotes | Daily (U.S. trading days) | Effective: U.S. trading day; available: usually by 6:00 PM ET, may be delayed | Archives published | Web / XML | None documented (public) | None | Public (no fee stated) | U.S. government data; terms not reviewed | Low volume | Not 15M; `DAILY_CONTEXT` only | n/a | VERIFIED |
| U.S. Treasury intraday (official) | U.S. Treasury | [S-31] [S-32] | None | No intraday series | — | — | — | — | — | — | — | — | — | Not suitable | n/a | NOT SUITABLE |
| U.S. Treasury intraday (proxy) | Treasury futures via KIS overseas futures; licensed vendor | [S-19] [S-20] | None | Futures prices, not yields | Minute (sample) | Time zone / DST UNVERIFIED | UNVERIFIED | Both | UNVERIFIED | KIS key; account type UNVERIFIED | CME real-time paid (price UNVERIFIED) | Exchange data licensing UNVERIFIED | UNVERIFIED | Possibly (proxy) | n/a | UNVERIFIED |
| Overseas futures / index signal | KIS overseas futures / index | [S-19] [S-20] [S-24] | None | Minute bars incl. 15-minute grouping; real-time stream | 1–60 min | Time zone / DST UNVERIFIED | Paging via index key; depth UNVERIFIED | Both | UNVERIFIED | KIS key; account type UNVERIFIED | CME / SGX real-time paid | UNVERIFIED | UNVERIFIED | Native 15M possible (PARTIALLY) | n/a | PARTIALLY VERIFIED |
| Market breadth | KIS `H0UPCNT0` | [S-11] | None | Issue-count fields per index | Per index update | `bsop_hour`; semantics inferred | Live only | Stream | Shares WebSocket session capacity (UNVERIFIED) | WS approval key | UNVERIFIED | UNVERIFIED | Must persist own snapshots | Suitable if sampled at slot (PARTIALLY) | n/a | PARTIALLY VERIFIED |
| Theme aggregation | Internal derivation | §5 sources | Theme tables exist; no intraday bars | Derived from member bars | As member bars | Inherits member bars | Inherits | — | Scales with member count | — | None | None | Same as bars | Suitable once §5 bars exist | n/a | PARTIALLY VERIFIED |
| Trading calendar / session | KIS `chk-holiday`, `market-time`, `H0STMKO0`; KRX holiday page; KRX regulation pages; KIS hours guide | [S-07] [S-08] [S-09] [S-27] [S-28] [S-30] | None (DAILY infers trading days from bars) | Business / trading / open / settlement flags; official holiday list | Daily ("가급적 1일 1회") | Date-level | KRX page lists 2016–2026 years | REST / web | `chk-holiday`: about once per day requested by KIS | KIS key (API); none (KRX page) | UNVERIFIED | UNVERIFIED | Low | Session-level input | n/a | PARTIALLY VERIFIED |

## 12. Read-Only / Credential Boundary

CURRENT REPOSITORY VERIFIED behaviour of the existing guard, and what it would mean for intraday sources. The guard is a substring deny rule, not a positive allowlist: every path that contains `/quotations/` and not `/trading/` passes, and neither the host nor the TR ID is checked. "Passes the current guard" therefore does not mean "safe".

| Path / channel | Passes current guard? | Note |
| --- | --- | --- |
| `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` (and other stock quotations, `chk-holiday`, `market-time`) | Yes | Contains `/quotations/` |
| `/uapi/domestic-futureoption/v1/quotations/...` | Yes | Contains `/quotations/` |
| `/uapi/overseas-futureoption/v1/quotations/...` | Yes | Contains `/quotations/` |
| `/oauth2/Approval` (WebSocket approval key) | Yes | Not under `/uapi/`; the approval key is derived from the app key / secret [S-02] |
| WebSocket subscription frames | **Not inspected** | The guard checks HTTP paths only; WebSocket TR IDs are JSON fields after connect. KIS samples also include account-linked real-time notifications (for example execution notices), so a WebSocket read-only boundary would need its own TR allowlist (PROPOSED; design belongs to a later gate) |
| Non-KIS providers (Treasury, BOK, SMBS, vendors) | Not covered | Need a separate read-only client / allowlist (PROPOSED) |

Credential observations:

- KIS tokens are valid 1 day; requesting again within 6 hours returns the same token; every issuance sends a KakaoTalk notification (알림톡) to the account holder. [S-02] A long intraday session must reuse tokens and must not re-issue on every reconnect. The current `KisAuthRepository` already reuses a stored token until expiry minus the 5-minute margin (CURRENT REPOSITORY VERIFIED, §2); a probe may reuse that behaviour only where it is compatible with the probe's credential boundary.
- WebSocket use requires an approval key requested with the same app key / secret. [S-02]
- Index-futures real-time (`H0IFCNT0`) works only with real-account credentials [S-15]; `H0CFCNT0` environment support is UNVERIFIED [S-16]. A PRODUCTION app key belongs to a real brokerage account; the read-only boundary (no `/trading/`, no order TRs, no account notices) becomes more important, not less. The initial 12-B2 probe uses VIRTUAL credentials only (§21.1, A-3).
- Paid overseas data is tied to the KIS account after subscription. [S-20]
- Fallback B (external executor, §27.1): secrets would leave the Android Keystore and need a new custody model and threat review (docs/160 §27 table). F-23 applies.

Positive read-only boundary required for any 12-B2 probe (HUMAN APPROVED — REQUIRED, §21.1 A-5; no code change in 12-B1; exact values are enumerated only in the 12-B2 execution instruction):

| Channel | Must be explicitly authorized |
| --- | --- |
| REST | Allowed host(s); allowed path(s); allowed quotation TR IDs. Anything not listed is rejected. No generic "everything under `/quotations/` is safe" assumption. KIS `/trading/` stays forbidden |
| WebSocket | Allowed endpoint / host; the approval-key acquisition boundary (`/oauth2/Approval`, derived from the app key / secret); allowed quotation TR IDs (account-linked TRs such as execution notices excluded); allowed environment (VIRTUAL / PRODUCTION) |
| Credentials | Which credential class the probe may read; approval key and token held only in memory or existing encrypted storage; never logged; never copied into docs or evidence files |
| Non-KIS sources | Separate allowlisted client per source (host and path), if any non-KIS source class is authorized |

## 13. Rate Limit / Universe Feasibility

Documented facts:

- `EGW00201` "초당 거래건수 초과" exists; "모의투자 계좌는 REST API 호출 제한이 낮습니다" (no number). OFFICIAL EXTERNAL VERIFIED. [S-01]
- KIS sample pacing: 0.05 s between calls (production), 0.5 s (demo); the sample also sets 0.1 s as a default. PARTIALLY VERIFIED (sample behaviour, not a published limit). [S-02]
- KIS sample WebSocket code raises "Subscription's max is 40" when `len(open_map.keys()) > 40`. The keys are distinct subscribed function / TR-type names (`request.__name__`), and each key holds a list of ticker items, so the check limits the number of subscription types in the sample, not instruments. It is proven neither as an instrument limit nor as an official session / account limit. PARTIALLY VERIFIED (sample behaviour only). [S-02]
- Official per-session / per-app-key WebSocket instrument-registration limit: **UNVERIFIED**. Third-party reports are not evidence.
- `chk-holiday`: KIS asks for about one call per day. OFFICIAL EXTERNAL VERIFIED. [S-07]
- BJStock's own pacing: 100 ms (PRODUCTION), 500 ms (VIRTUAL); `EGW00201` retry waits 61 s. CURRENT REPOSITORY VERIFIED.

Acquisition load per 15M slot (calls are per instrument; one today-minute call returns up to 30 rows, enough for the last 15 minutes). Times are lower bounds from pacing alone; network round-trip time is unknown and must be measured.

| Universe | 1-minute REST calls per slot | Min. sequential time at BJStock 100 ms | at BJStock 500 ms (VIRTUAL) | at KIS sample 50 ms | Native 15M source | Trade-stream registrations needed | WebSocket subscription capacity | Provider / session limits |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 | 10 | ≥ 1.0 s | ≥ 5 s | ≥ 0.5 s | Same call count (no batch endpoint verified) | 10 + any futures / index / breadth | UNVERIFIED — must be measured / officially confirmed | UNVERIFIED |
| 30 | 30 | ≥ 3.0 s | ≥ 15 s | ≥ 1.5 s | Same | 30 + any futures / index / breadth | UNVERIFIED — must be measured / officially confirmed | UNVERIFIED |
| 50 | 50 | ≥ 5.0 s | ≥ 25 s | ≥ 2.5 s | Same | 50 + any futures / index / breadth | UNVERIFIED — must be measured / officially confirmed | UNVERIFIED |
| 100 | 100 | ≥ 10 s | ≥ 50 s | ≥ 5 s | Same | 100 + any futures / index / breadth | UNVERIFIED — must be measured / officially confirmed | UNVERIFIED |

Notes:

- REST polling demand is calculable from the selected polling design (the columns above are pacing-only lower bounds). WebSocket subscription capacity and provider / session limits are UNVERIFIED for every universe size, including 10; no size can be called "within" or "over" a provider limit on current evidence. 12-B2 measurement or further official evidence must resolve capacity. The universe hard cap remains a measure-first decision (group B, §21).
- A native 15M source would reduce rows, not calls, unless a multi-instrument endpoint exists; none was verified for Korean stocks.
- Futures, index, breadth, FX-proxy, and any Option A polling add calls / registrations on top of the stock universe.
- One `EGW00201` under the current 61 s retry wait would consume a large part of any plausible decision window; the DAILY retry policy cannot be reused unchanged for intraday (engineering item for 12-D / 12-J; no value selected here).
- Warm-up / backfill (illustrative arithmetic, not a decision; assumes the requested days are within KIS's retention): one regular session is about 390 one-minute rows, so at 120 rows per call about 4 calls per instrument-day. Twenty trading days would be about 800 / 2,400 / 4,000 / 8,000 calls for 10 / 30 / 50 / 100 instruments, i.e. at least 80 / 240 / 400 / 800 s at 100 ms pacing.
- Factor-calculation load is local arithmetic over N instruments × a short bar window per slot and is expected to be small relative to acquisition; it is not measured in 12-B1, and 12-B2 is forbidden from strategy evaluation, so it remains an estimate until 12-F / 12-N.
- WebSocket message rate for liquid stocks (trades per second) is UNVERIFIED and drives device CPU, battery, and network load.

Undocumented limits (UNVERIFIED): REST requests per second for real and demo accounts; WebSocket registrations per session and sessions per app key; WebSocket message throughput; daily request quotas; overseas-futures limits; token issuance limits beyond the 6-hour reuse rule.

## 14. Execution Option A Feasibility

Requirement (§21): `execution_price_time > order_created_at`, with a provider-verifiable eligible price.

**Option A is acceptable only when BJStock can prove strict-after. If the proof fails, the result is NO FILL.** No approximation, no backfill, no substitution from a bar or a later reconstruction. A switch to Option B requires a later explicit Human approval (§36 #3).

### 14.1 Clock and evidence domains

| # | Domain | What it provides | Known gaps (current evidence) |
| --- | --- | --- | --- |
| 1 | Android / device wall clock | `order_created_at` | The wall clock may jump (network time, NTP, user change); the device–exchange offset is not currently bounded; drift during a session is unknown |
| 2 | KIS / exchange trade timestamp | `STCK_CNTG_HOUR` HHMMSS, second resolution [S-06] | Time zone (KST) implied, not stated; whether sub-second time is truncated (floor) or rounded is undocumented |
| 3 | Provider transport / arrival | Device receipt time of each frame | Provider and network delay are unknown; arrival time is not trade time |
| 4 | Stream ordering / batching / reconnect state | Frames from `H0STCNT0` | No documented sequence field; frames carry a record count whose completeness semantics are unverified (the sample ignores it) [S-02]; ordering is not documented; the sample's reconnect loop resubscribes after a short sleep, so a reconnect may create a silent gap; demo-vs-real fidelity unverified |

Current evidence is insufficient to prove strict-after until BJStock can establish every relevant assumption across these four domains. Consequences of the gaps:

- If a reconnect gap or an undetected batch drop occurs, the first received strictly-later trade may not be the first actual one; whether such a price may still count must be settled by evidence and a later Human rule, and until then the fail-closed outcome applies.
- Auction, VI, and halt / resume prints carry fields (`HOUR_CLS_CODE`, `NEW_MKOP_CLS_CODE`, `MRKT_TRTM_CLS_CODE`, `TRHT_YN`, `VI_STND_PRC`) whose semantics are UNVERIFIED; eligibility is OPEN.

### 14.2 Same-second fail-closed rule (feasibility rule, no margin set)

- Example: `order_created_at` = 10:15:12.700 and trade timestamp = 10:15:12. This can **never** prove strict-after, whatever the clock offset.
- A trade timestamp of 10:15:13 is sufficient only when both the timestamp semantics (for example, proven truncation rather than rounding; with rounding, "10:15:13" could cover 10:15:12.5–10:15:13.5) and a bounded device / exchange clock offset make that inference safe.
- No margin is chosen here. 12-B2 must collect enough evidence (§19, M-07 and M-19 to M-26) to derive a safe rule, which then needs a Human decision.
- Architecture-safe fallback: if strict-after cannot be proven, **NO FILL**.

### 14.3 Candidate evidence paths

| Path | Evidence | Assessment |
| --- | --- | --- |
| WebSocket `H0STCNT0` trade after the order | `STCK_CNTG_HOUR` HHMMSS + `STCK_PRPR` [S-06] | Usable in principle, subject to §14.1 and §14.2. Same-second trades are excluded; the rule for later seconds must be derived from 12-B2 evidence. PROPOSED only |
| WebSocket `H0NXCNT0` / `H0UNCNT0` | NXT / integrated trade streams with `STCK_CNTG_HOUR` [S-56] [S-57] | PARTIALLY VERIFIED candidates; relevant only if venue ≠ `J` or NXT / integrated execution is considered later; not approved fallbacks |
| REST recent trades | `inquire-ccnl`, TR `FHKST01010300` [S-55] | PARTIALLY VERIFIED candidate; output fields undocumented in the sample (UNVERIFIED); relevant if `H0STCNT0` is not authorized or not sufficient; not an approved fallback |
| REST time-bucketed trades | `inquire-time-itemconclusion` [S-05] | Output fields undocumented in the sample; UNVERIFIED |
| REST current price | `inquire-price`; no intraday time mapped in BJStock DTOs | Cannot prove time ordering; NOT FEASIBLE WITH THIS DATA SOURCE as currently mapped |
| 1-minute bars | Bar granularity only | Cannot prove a price strictly after an intra-minute order without the trade stream; relevant to Option B only |

Additional factors:

- `order_created_at` is a device-clock instant; `STCK_CNTG_HOUR` is a provider / exchange time. Clock offset and drift must be measured against a stated reference clock (§12 engineering item in docs/160; §19 M-19, M-20).
- Illiquid instruments may have no trade for a long time; interplay with expiry and pending exposure (§20.1, §36 #12) is a later-gate design item.
- Volatility interruptions (`VI_STND_PRC`), trading halts (`TRHT_YN`), and the closing call auction (15:20–15:30) suspend continuous trading; whether an auction print counts as an "eligible price" is measured first, then decided by the Human (§21 group B).
- Demo-environment trade-stream fidelity versus real market data is UNVERIFIED.

Classification: **POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE.** Not VERIFIED: the proof depends on measurements that do not exist yet. Not downgraded: nothing found makes the proof impossible. Unprovable price ⇒ NO FILL. Switching to Option B requires a later explicit Human decision (§36 #3); nothing here proposes switching.

## 15. Android Foreground Platform Constraints

Platform: `targetSdk 36`, `minSdk 28` (CURRENT REPOSITORY VERIFIED). OS enforcement and Play policy are separated below.

### 15.1 Candidate foreground-service types

| Type | OS enforcement | Play policy | Why BJStock qualifies / may not | Assessment |
| --- | --- | --- | --- | --- |
| `specialUse` | Needs `FOREGROUND_SERVICE_SPECIAL_USE` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation; **no time limit documented** on the timeout page (which covers `dataSync`, `mediaProcessing`, `shortService`) [S-35] [S-36] | If distributed through Google Play: the subtype is reviewed in Play Console; Play says it is for "limited scenarios" and "all foreground service types are subject to review" [S-35] [S-51] | Covers valid uses not covered by other types; a user-started, user-visible market-session monitor for paper trading is not described by any other type. Play acceptance is UNVERIFIED | HUMAN APPROVED as the initial 12-B2 probe candidate only (§21.1, A-2); not the production selection |
| `dataSync` | Needs `FOREGROUND_SERVICE_DATA_SYNC`; **6 hours per 24 hours in the background, then `onTimeout()`**; timer resets only when the user brings the app to the foreground; cannot start from `BOOT_COMPLETED` (targetSdk 35+) [S-35] [S-36] | Play lists data fetch / upload / download, emphasising user-initiated work; suggests user-initiated data-transfer jobs for explicit network transfers [S-51] | "Fetch data" fits the network part, but **FLAG: a 6.5-hour KRX session plus any pre-open start exceeds 6 hours** unless the user foregrounds the app during the session | Possible only with a foreground-visit workaround; risky |
| `mediaProcessing` | 6 hours per 24 hours | Media transcoding | Not media work | NOT SUITABLE |
| `shortService` | About 3 minutes | — | Session is hours | NOT SUITABLE |
| `systemExempted` | "Reserved for system applications and specific system integrations"; eligibility criteria listed by Android include demo mode, device owner, profile owner, emergency-role safety apps, device admin, VPN apps, and "Apps holding `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` permission"; otherwise `ForegroundServiceTypeNotAllowedException`. Not listed on the timeout page [S-35] [S-36] | `USE_EXACT_ALARM` is "Subject to an upcoming Google Play policy" and limited to alarm / calendar use cases; Play declaration and review apply to FGS types [S-48] [S-49] [S-51] | For a personal sideloaded APK, the OS eligibility path exists through an exact-alarm permission (`SCHEDULE_EXACT_ALARM` granted by the user, or `USE_EXACT_ALARM`). Whether holding an exact-alarm permission mainly to qualify is acceptable is a policy / product judgement; Play distribution would be problematic | OS-CANDIDATE / HUMAN-POLICY DECISION / NEEDS VALIDATION (not selected) |
| `connectedDevice` | Runtime prerequisites apply | Interaction with external devices | No external device | NOT SUITABLE |
| `remoteMessaging`, `location`, `health`, `camera`, `microphone`, `mediaPlayback`, `phoneCall`, `mediaProjection` | Type-specific prerequisites (for example, `health` needs sensor / health permissions, changed for apps targeting Android 16) [S-35] [S-45] | Type-specific use cases | Not BJStock's use | NOT SUITABLE |

**FLAG (duration ≤ session):** `dataSync` and `mediaProcessing` allow 6 hours per 24 hours in the background; the KRX regular session is 6.5 hours. This is OS enforcement for apps targeting Android 15+, independent of distribution channel, and remains a material concern for `dataSync`. [S-36]

No foreground-service type is selected as the final production type (`specialUse`, `systemExempted`, and `dataSync` all remain unselected for production). The Human approved `specialUse` as the initial 12-B2 probe candidate only (§21.1, A-2); `systemExempted` is not selected, and the `dataSync` 6-hour concern remains. The final production type is measure-first (B-10) and is chosen after 12-B2.

### 15.2 Other OS constraints

| Area | Documentation | Label | Source |
| --- | --- | --- | --- |
| Start restrictions | Apps targeting Android 12+ cannot start a foreground service while running in the background except in listed exemptions (otherwise `ForegroundServiceStartNotAllowedException`). A session started by the user from a visible activity is allowed | OFFICIAL EXTERNAL VERIFIED | [S-37] [S-39] |
| Exemptions relevant to the design | Listed exemptions include: the user acts on a UI element related to the app (notification, widget, activity); the app invokes an exact alarm to complete an action the user requested; "The user turns off battery optimizations for your app"; `BOOT_COMPLETED` (with type restrictions; `dataSync` excluded for targetSdk 35+) | OFFICIAL EXTERNAL VERIFIED | [S-35] [S-37] |
| Recovery after process death | Whether and how a session can resume after process death while the app is in the background is not stated directly; it follows only by inference from the generic start restriction and its exemptions | PARTIALLY VERIFIED (inference) / MUST MEASURE (12-B2, M-11) | [S-37] |
| Declaration | Type must be declared in the manifest, or `startForeground()` throws `MissingForegroundServiceTypeException`; undeclared type passed → `IllegalArgumentException` | OFFICIAL EXTERNAL VERIFIED | [S-38] [S-39] |
| Notification | Foreground service shows a status-bar notification of priority `PRIORITY_LOW` or higher. On Android 13+, `POST_NOTIFICATIONS` is a runtime permission; if denied, the notice appears only in the Task Manager, not the drawer | OFFICIAL EXTERNAL VERIFIED | [S-39] [S-41] |
| User stop | Android 13+ Task Manager "Stop" removes the whole app from memory with no callback; on restart `ApplicationExitInfo` reports `REASON_USER_REQUESTED` | OFFICIAL EXTERNAL VERIFIED | [S-40] |
| Doze | Suspends network access, ignores wake locks, defers standard alarms, jobs, and syncs; maintenance windows become less frequent | OFFICIAL EXTERNAL VERIFIED | [S-42] |
| Doze with an active foreground service | Not explicitly stated in reviewed pages; a foreground service by itself is not documented to guarantee network access or CPU time | UNVERIFIED — must be measured | [S-42] [S-43] |
| App Standby | Not applied while the app has a foreground process (activity or foreground service). Docs warn not to start a foreground service merely to avoid idle detection | OFFICIAL EXTERNAL VERIFIED | [S-42] |
| Jobs with an FGS | Android 16: job runtime quota applies to jobs running while a foreground service runs | OFFICIAL EXTERNAL VERIFIED | [S-43] [S-44] |
| In-process timers | `Handler.postAtTime()` / `postDelayed()` use system uptime, not real time | OFFICIAL EXTERNAL VERIFIED | [S-48] |
| Wake locks | Partial wake locks keep the CPU on after screen-off; strong battery impact; Android vitals tracks excessive partial wake locks | OFFICIAL EXTERNAL VERIFIED | [S-46] [S-47] |
| Battery optimization | Users may exempt an app; apps on the power allowlist may also set exact alarms. Play policy restricts direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requests | OFFICIAL EXTERNAL VERIFIED (OS) / PARTIALLY VERIFIED (policy detail) | [S-42] [S-49] |
| OEM battery management | Not documented by Google | UNVERIFIED — measure on the physical device | — |

### 15.3 Android 17 note

The project compiles against SDK 37 and targets SDK 36, so devices running Android 17 are in scope. Findings from the reviewed Android 17 behaviour-change pages; an item "not found in reviewed official pages" is not proof of universal absence:

| Item | Finding | Label | Source |
| --- | --- | --- | --- |
| FGS type / timeout / Doze | No reviewed change requires a different foreground-service type for targetSdk 36, and no reviewed change alters the FGS timeout or Doze behaviour used in this document | NOT FOUND IN REVIEWED OFFICIAL PAGES (not proof of universal absence) | [S-52] [S-53] |
| Memory limiter (all apps) | "Android 17 introduces app memory limits based on the device's total RAM"; if affected, the exit reason is `REASON_OTHER` and `ApplicationExitInfo.getDescription()` contains "MemoryLimiter:AnonSwap" | OFFICIAL EXTERNAL VERIFIED | [S-52] |
| Changes for apps targeting Android 17 | Examples: `ACCESS_LOCAL_NETWORK` enforcement, Encrypted Client Hello, certificate transparency by default. They apply only when targeting SDK 37 and are not adopted here | OFFICIAL EXTERNAL VERIFIED | [S-53] |

Implication: the memory limiter is a possible process-exit cause on Android 17 devices; 12-B2 process-survival evidence (M-11) must record `ApplicationExitInfo` reason and description, including `REASON_OTHER` / "MemoryLimiter" where applicable.

## 16. Personal APK vs Play Distribution

| Topic | Personal APK (sideload) | Play distribution |
| --- | --- | --- |
| OS rules (type declaration, 6-hour limit, background-start limits, notification permission, Doze) | Apply in full | Apply in full |
| FGS type declaration in Play Console (targetSdk 34+), description, user impact, demo video | Not applicable | Required [S-51] |
| `specialUse` review | Not applicable | Required; outcome UNVERIFIED [S-35] |
| `USE_EXACT_ALARM` policy, battery-optimization request policy | Not applicable | Applies [S-48] [S-49] |
| Android vitals (excessive wake locks) | Not applicable | Applies [S-47] |
| `systemExempted` through an exact-alarm permission | OS eligibility path exists (`SCHEDULE_EXACT_ALARM` user grant or `USE_EXACT_ALARM`) [S-35]; acceptability is a Human policy decision | `USE_EXACT_ALARM` subject to Play policy (alarm / calendar use cases) [S-48] [S-49] |
| Android developer verification | "Effective September 30, 2026" for participating stores in Brazil, Indonesia, Singapore, Thailand on certified devices; global expansion "in 2027" [S-50]. Official FAQ: "Apps installed using ADB won't require verification", and "As a developer, you are free to install apps without verification with ADB" [S-54]. Limited-distribution accounts allow sharing with up to 20 devices without ID verification [S-50] [S-54]. Effect on a non-ADB sideloaded APK in Korea from 2027: OPEN / UNVERIFIED detail | Play registers most apps automatically [S-50] |

Implications:

- A personal APK avoids Play review of `specialUse`, but not any OS limit.
- Developer verification is a distribution matter, separate from Android OS runtime rules and from Play policy.
- For the 12-B2 disposable probe, ADB installation remains a viable installation path (no developer verification required per the official FAQ); ADB installation by the user is an approved user-side action (§21.1, A-10).
- The final BJStock distribution path is a Human decision that can wait until after 12-B2 (§21 group C).

## 17. Exact Alarm Research

| Item | Finding | Source |
| --- | --- | --- |
| Permission default | `SCHEDULE_EXACT_ALARM` is not pre-granted to new installs targeting Android 13+ on Android 14+; user grants it in Settings ("Alarms & reminders") | [S-49] [S-48] |
| `USE_EXACT_ALARM` | Granted at install, cannot be revoked, restricted by Play policy to alarm / calendar use cases | [S-48] [S-49] |
| Revocation | If `SCHEDULE_EXACT_ALARM` is revoked, the app stops and future exact alarms are cancelled | [S-48] |
| Doze | Standard alarms deferred; `setExactAndAllowWhileIdle()` fires in Doze but while-idle alarms are rate limited (power-details table: "Limited to 7 per hour"); `setAlarmClock()` exits Doze | [S-42] [S-43] |
| FGS start | "exact alarms aren't affected by foreground service launch restrictions" | [S-48] |
| Exemptions | Platform-signed, privileged, and power-allowlisted apps may call `setExact()` | [S-49] |
| `OnAlarmListener` | Exact alarm set with an `OnAlarmListener` does not require `SCHEDULE_EXACT_ALARM` | [S-49] |

Relevance: the approved candidate A is a user-started foreground session, which does not need exact alarms. Exact alarms matter only for C1 (per-slot alarms) or an automatic pre-open start, both of which need a new Human decision (docs/160 §27). Status: **DEFERRED** unless the Human wants auto-start or C1. Holding an exact-alarm permission also bears on `systemExempted` eligibility (§15.1); that link is recorded for the Human, not used as a reason to adopt exact alarms.

## 18. KRX Session / Calendar

| Item | Finding | Label | Source |
| --- | --- | --- | --- |
| Regular session | 09:00–15:30 (KOSPI / KOSDAQ / KONEX) | OFFICIAL EXTERNAL VERIFIED (KIS guide); KRX general-rules tab content not extractable | [S-27] [S-29] |
| Auctions | 동시호가 08:30–09:00 and 15:20–15:30; order acceptance from 08:20 listed | OFFICIAL EXTERNAL VERIFIED (KIS guide) | [S-27] |
| Pre-open closing-price trading | 08:30–08:40 at the previous close | OFFICIAL EXTERNAL VERIFIED | [S-30] [S-27] |
| After-hours closing-price trading | 15:40–16:00 (orders 15:30–16:00) at the day's close | OFFICIAL EXTERNAL VERIFIED | [S-30] |
| After-market | 16:00–20:00, continuous trading, ±30 % of base price, excludes listed categories | OFFICIAL EXTERNAL VERIFIED | [S-30] |
| Recent regime change | KIS notices titled "KRX 애프터마켓 도입 및 NXT 제도 변경에 따른 안내" (2026-09-09) and "API 변경에 따른 안내" (2026-08-27) | PARTIALLY VERIFIED (titles only) | [S-26] |
| NXT venue hours | Official NXT source not reviewed | UNVERIFIED | — |
| Holiday calendar | KIS `chk-holiday` (TR `TCA0903R`) returns business-day, trading-day, open-day (`opnd_yn`), settlement-day flags; about one call per day; KRX publishes an official holiday page (years 2016–2026, table rendered dynamically) | OFFICIAL EXTERNAL VERIFIED (KIS) / PARTIALLY VERIFIED (KRX content) | [S-07] [S-28] |
| Special sessions (delayed open, shortened days) | No official source reviewed | UNVERIFIED | — |

Session-boundary concerns for later gates (no values chosen):

- First 15M boundary: with a 09:00 open (opening-auction print), the first bar would be 09:00–09:15 and the first evaluation slot 09:15 (docs/160 §31 example). Whether KIS places the opening print in a minute bar labelled 09:00 or 09:01 is UNVERIFIED (12-B2).
- The 15:15–15:30 bar contains 5 minutes of continuous trading and the closing auction (single print at 15:30). Confirmation of that bar happens after the session ends.
- A 15:30 slot order cannot find a regular-session price strictly after it (after-hours trading is DEFERRED in the MVP). Orders created during 15:20–15:30 could only fill at the closing print if that print is eligible. Last new-entry slot and forced close remain OPEN.
- VI, halts, and circuit breakers create intraday periods without continuous trades.
- Venue: `J` (KRX) is consistent with the KRX-only MVP; `UN` / `NX` would bring NXT prices into the basis (OPEN).

Area recommendation: **GO TO 12-B2** for the calendar source check; special-session sources **NEEDS MORE PROVIDER RESEARCH**.

## 19. 12-B2 Measurement Requirements

No pass thresholds are proposed and no measurement method or reference is selected here. Each line separates what documentation already tells us from what must be observed. Kind: RAW = an independent observation logged by the probe; DERIVED = a computation over RAW observations (not an independent measurement). "Depends on" names the §21 group-A decision that must exist before the line can be measured ("—" = none beyond the probe itself).

| # | Measurement | Kind | Depends on | DOCUMENTATION KNOWN | MUST BE OBSERVED IN 12-B2 |
| --- | --- | --- | --- | --- | --- |
| M-01 | Provider observation delay after 1-minute bar close | RAW | Credential class; instrument set | In-progress first row caveat; no latency figure | Distribution of (first time a bar is complete in REST) − (bar end) |
| M-02 | REST latency | RAW | Credential class | None | Round-trip time per endpoint and environment, by network type |
| M-03 | WebSocket arrival delay | RAW | WebSocket yes; credential class | Second-resolution trade time | (device receipt time) − (`STCK_CNTG_HOUR`), reported together with the M-19 offset |
| M-04 | Cutoff grace evidence | DERIVED (M-01, M-02, M-03, M-19) | As inputs | Depends on inputs | Evidence summary only; the grace value stays a Human decision (group B) |
| M-05 | `max_age` evidence per source | DERIVED (per-source update gaps and staleness logged by the probe) | Source classes | Treasury daily cadence and publication timing; KIS cadences | Evidence summary only; values stay a Human decision (group B) |
| M-06 | Deadline evidence | DERIVED (M-01, M-02, M-18 timing logs) | As inputs | `decision_deadline_at < next_slot_at` fixed | Distribution of `decision_at − slot_at` (probe-simulated, no model) |
| M-07 | Option A: first provably-later trade | DERIVED (M-03, M-19 to M-25) | WebSocket yes; instrument set | Trade stream fields | Time from a simulated `order_created_at` to the first trade that is provably later under the evidence gathered, per liquidity class; share of slots where no provable trade exists (→ NO FILL) |
| M-08 | Bar labelling / 허봉 | RAW | Instrument set | Parameters exist | Opening-print bar label; closing-auction representation in bars; 허봉 effect |
| M-09 | Screen-off behaviour with proof of idle | RAW | FGS type | Doze rules | Slot timer accuracy and network availability with screen off, together with logged evidence that the device actually entered Doze / idle (platform idle-state signal) and for how long |
| M-10 | Network transitions | RAW | — | None | Wi-Fi ↔ mobile switch, loss, reconnect timing |
| M-11 | Process survival and exit reasons | RAW | FGS type | Task Manager stop, background-start limits, Android 17 memory limiter | Every process exit with `ApplicationExitInfo` reason and description (including `REASON_USER_REQUESTED`, and `REASON_OTHER` with "MemoryLimiter" on Android 17); unrecovered deaths |
| M-12 | Session survival | RAW | FGS type (Human-selected candidate) | `dataSync` 6-hour background limit; `specialUse` none documented | Full 09:00–15:30 survival under the candidate type; any `onTimeout()` |
| M-13 | Battery | RAW | FGS type | Wake-lock guidance | Battery drain per session, screen-off share |
| M-14 | Notification behaviour | RAW | FGS type | Notification and permission rules | Whether the FGS notification is visible in the drawer and / or Task Manager with `POST_NOTIFICATIONS` granted and denied; count of user actions the session required |
| M-15 | REST pacing | RAW | Credential class; instrument set | `EGW00201` exists; no numbers | `EGW00201` occurrences at the chosen pacing and probe universe size |
| M-16 | Token / approval lifecycle | RAW | Credential class; WebSocket yes | 1-day token; 6-hour reuse; 알림톡 on issuance | Token issuances and approval-key requests per session; reconnect without re-issuance |
| M-17 | Breadth / futures field semantics | RAW | Source classes; credential class (`H0IFCNT0` index-futures real-time is real-account-only; `H0CFCNT0` environment UNVERIFIED). Not in the initial probe (A-7) | Field names | Confirm `H0UPCNT0` issue counts and futures `bsop_hour` meaning |
| M-18 | Slot completion stats (§27.1 shape) | DERIVED (slot logs) | As inputs | Shape approved | % slots before deadline; worst delay; max consecutive LATE / MISSED / FAILED |
| M-19 | Device clock offset | RAW | — | Not documented | Offset of the device wall clock against a stated external / reference clock (reference chosen under 12-B2 authority) |
| M-20 | Clock drift and jumps | RAW | — | Not documented | Offset drift during the session; any wall-clock jump events |
| M-21 | HHMMSS semantics | RAW (or official confirmation) | WebSocket yes | Second resolution only | Whether `STCK_CNTG_HOUR` truncates (floor) or rounds sub-second time |
| M-22 | Same-second trade rate | DERIVED (M-03 stream logs, simulated order times) | WebSocket yes; instrument set | None | Share of simulated orders whose next trade falls in the same second (never proof) |
| M-23 | Batching, record count, ordering | RAW | WebSocket yes | Frame count field exists; semantics undocumented | Record-count values per frame; whether frames batch multiple trades; out-of-order timestamps |
| M-24 | Gaps, reconnect, resubscribe loss | RAW | WebSocket yes | No sequence field documented | Whether gaps can be detected (for example, by a cumulative-volume continuity cross-check, PROPOSED); trades missed across reconnect / resubscribe |
| M-25 | Auction / VI / halt-resume print semantics | RAW | WebSocket yes; instrument set | Field names only | How opening / closing auction prints, VI prints, and halt / resume prints appear (`HOUR_CLS_CODE`, `NEW_MKOP_CLS_CODE`, `MRKT_TRTM_CLS_CODE`, `TRHT_YN`, `VI_STND_PRC`) |
| M-26 | Demo vs real fidelity | RAW | Both environments authorized (not in the initial probe, which is VIRTUAL only, A-3) | Not documented | Differences between VIRTUAL and PRODUCTION streams / bars for the same instruments and times (only if both are authorized) |
| M-27 | WebSocket subscription capacity | RAW (or official confirmation) | WebSocket yes; instrument set | No official number; the sample `> 40` check counts TR-type keys | Accept / reject responses at the authorized probe subscription size |
| M-28 | Run context record | RAW | Device | — | Device model; Android OS version and build; app version / commit; OEM battery-manager state and battery-optimization state, recorded for every run |
| M-29 | Thermal state | RAW (if observable) | Device | — | Thermal status / throttling events during the session where the platform exposes them |
| M-30 | Network / data usage | RAW | — | None | Bytes sent / received per session by network type |

Prerequisites for 12-B2: exactly the group-A decisions in §21, all HUMAN APPROVED on 2026-10-02 (§21.1), plus a separate 12-B2 execution instruction. Only lines compatible with the approved initial scope (VIRTUAL credentials, domestic stocks, venue `J`, two instruments) are in the first probe; M-17 and M-26 are outside it. Nothing is implemented now. Where a measurement needs user-side device actions (notification permission, battery settings), the agent must stop and instruct the user rather than change device settings.

## 20. Feasibility Risks

| Risk | Severity (research view) | Note |
| --- | --- | --- |
| `dataSync` 6-hour limit < 6.5-hour session | HIGH if `dataSync` chosen | OS rule; foreground visits reset it |
| `specialUse` Play review rejection | MEDIUM (Play only) | Personal APK unaffected |
| Doze / OEM kills network or process with screen off | HIGH until measured | Fail-Closed handles it, but slots go MISSED |
| Undocumented KIS limits | MEDIUM | 61 s retry wait incompatible with slot windows |
| WebSocket subscription capacity unknown | MEDIUM for any universe size until measured / officially confirmed | No official number; the sample `> 40` check counts TR-type keys, not instruments |
| Option A strict-after proof (clock offset, HHMMSS semantics, batching, gaps, reconnects) | MEDIUM | Unprovable ⇒ NO FILL; 12-B2 evidence (M-19 to M-26) needed before any rule |
| `H0IFCNT0` index-futures real-time real-account only (`H0CFCNT0` environment UNVERIFIED) | MEDIUM (only if futures are added later) | Per-stream credential-class decision; futures are excluded from the initial probe (A-7) |
| Paid overseas data / licensing | MEDIUM | Purchase is a Human decision |
| USD/KRW spot unavailable; KIS currency-futures access UNVERIFIED | MEDIUM | A futures proxy is never spot; source class can wait (group C) |
| Minute-bar labelling / auction placement unknown | MEDIUM | Wrong aggregation breaks point-in-time rules |
| KIS minute-bar retention ceiling (up to 1 year; actual depth UNVERIFIED) | LOW–MEDIUM | BJStock retention must cover replay |
| Recent KRX / NXT regime change (2026-09) | MEDIUM | API behaviour may have changed; re-verify |
| Token issuance notifications on reconnect | LOW | Must reuse tokens |
| Developer verification for non-ADB sideloaded APKs (2027 global) | LOW | ADB installs do not require verification (official FAQ); limited-distribution accounts exist |
| Android 17 memory limiter | LOW until measured | Possible process-exit cause; recorded via `ApplicationExitInfo` (M-11) |
| Current KIS guard is not a positive allowlist | MEDIUM for any new source | A probe needs explicit host / path / TR allowlists (§12) |
| Fallback B reopens source of truth and secret custody | Structural | F-23 |

## 21. Human Decisions

Every item belongs to exactly one group. Group-A items are the minimum needed to run a disposable 12-B2 probe; all of them were HUMAN APPROVED on 2026-10-02 (details in §21.1). Groups B and C remain OPEN; no value is chosen for them here.

**A. MUST DECIDE BEFORE 12-B2** (minimum needed to run the disposable probe) — all HUMAN APPROVED — 2026-10-02

| # | Decision | Notes | Status |
| --- | --- | --- | --- |
| A-1 | Explicit 12-B2 implementation authority, including its forbidden list | docs/160 §27.1 | HUMAN APPROVED — 2026-10-02 |
| A-2 | Candidate foreground-service type for the probe only | Not the final production type (§15.1) | HUMAN APPROVED — 2026-10-02 (`specialUse`, probe candidate only) |
| A-3 | KIS environment / credential class for the probe (VIRTUAL vs PRODUCTION) | Only `H0IFCNT0` index-futures real-time is VERIFIED real-account-only; `H0CFCNT0` environment support is UNVERIFIED (§6). Futures are not in the initial probe, so neither fact justifies PRODUCTION credentials for it | HUMAN APPROVED — 2026-10-02 (VIRTUAL only) |
| A-4 | WebSocket yes / no | Option A evidence depends on it (§14) | HUMAN APPROVED — 2026-10-02 (quotation WebSocket yes) |
| A-5 | Positive REST + WebSocket read-only allowlist (hosts, paths, TR IDs, environment) | §12; KIS `/trading/` stays forbidden | HUMAN APPROVED — 2026-10-02 (REQUIRED) |
| A-6 | Probe instrument set | Not the universe hard cap (B-4) | HUMAN APPROVED — 2026-10-02 (`005930`, `000660`) |
| A-7 | Probe source classes (stocks only, or also futures / index / breadth / Treasury `DAILY_CONTEXT`) | §5–§10 | HUMAN APPROVED — 2026-10-02 (domestic stocks only) |
| A-8 | Probe venue code (`J` / `NX` / `UN`) | §5 | HUMAN APPROVED — 2026-10-02 (`J` = KRX) |
| A-9 | Probe data isolation from the BJStock business source of truth, and disposal rules | — | HUMAN APPROVED — 2026-10-02 (REQUIRED) |
| A-10 | Physical device and owner of user-side actions (notification permission, battery settings) | Agent stops and instructs the user | HUMAN APPROVED — 2026-10-02 |
| A-11 | Trading-day test window(s) (real KRX sessions) | — | HUMAN APPROVED — 2026-10-02 (two normal KRX trading days) |

**B. MEASURE FIRST IN 12-B2** (the Human is not asked to guess these beforehand; all remain OPEN until measured)

| # | Decision | Evidence (§19) |
| --- | --- | --- |
| B-1 | Cutoff grace | M-04 |
| B-2 | Decision deadline | M-06, M-18 |
| B-3 | `max_age` per source | M-05 |
| B-4 | Universe hard cap | M-15, M-27, M-30 |
| B-5 | Option A second-boundary rule / clock-offset margin | M-07, M-19 to M-24 |
| B-6 | Eligibility of auction / VI / halt-resume prints, and 허봉 treatment | M-08, M-25 |
| B-7 | Final Option A vs Option B (B only by explicit Human approval) | M-07, M-22 |
| B-8 | Final Android vs external topology | M-09 to M-14, M-18 |
| B-9 | Intraday pacing / retry settings | M-02, M-15 |
| B-10 | Final production foreground-service type | M-09, M-11, M-12 |

**C. CAN WAIT UNTIL AFTER 12-B2** (all remain OPEN as later decisions)

| # | Decision |
| --- | --- |
| C-1 | Final distribution path (personal APK vs Play); the probe can be installed with ADB (§16) |
| C-2 | USD/KRW source class (§7) |
| C-3 | U.S. Treasury paid / intraday proxy (§8) |
| C-4 | Overseas futures paid subscription (CME / SGX) (§9) |
| C-5 | Futures front-month roll rule (§6) |
| C-6 | Exact alarms / automatic pre-open start (§17; DEFERRED otherwise) |
| C-7 | Transaction cost values |
| C-8 | Stop-loss / take-profit thresholds |
| C-9 | Forced close |
| C-10 | Last new-entry slot |
| C-11 | Production venue (venue code per production Run; the probe venue is A-8) |

**D. ALREADY FIXED BY docs/160** (not reopened here)

| # | Fixed decision | docs/160 |
| --- | --- | --- |
| D-1 | Strict time ordering `slot_at ≤ decision_cutoff_at ≤ decision_at ≤ order_created_at ≤ decision_deadline_at < next_slot_at` | §11, §36 #4 |
| D-2 | Option A preferred; Option B only by a later explicit Human decision | §21, §36 #3 |
| D-3 | KRX regular-session MVP | §31, §36 #5 |
| D-4 | Fail-Closed; MISSING never zero | §0.2, §36 #10 |
| D-5 | No retroactive trade; missed slots never reconstructed | §29, §30, §36 #12 |
| D-6 | Raw / native price-basis principle, never mixed within one Run | §36 #16 |
| D-7 | `DAILY_CONTEXT` allowed with explicit timeframe identity | §16, §36 #17 |
| D-8 | Multi-provider, KIS-first principle; no provider approved by candidacy | §14, §36 #9 |
| D-9 | Small explicit universe | §33, §36 #13 |
| D-10 | Android-first probe order: 12-B1 → 12-B2 → Human topology decision → 12-C | §27, §27.1, §36 #11 |
| D-11 | PAPER TRADING ONLY | §0.1, §4 |
| D-12 | KIS `/trading/` forbidden | §14, §36 #9 |

### 21.1 Human Approval Record — Group A (2026-10-02)

These approvals bound the first 12-B2 probe only. They do not start 12-B2: work begins only under a separate 12-B2 execution instruction. Nothing here selects a production value.

**A-1 — 12-B2 implementation authority: HUMAN APPROVED — 2026-10-02**

- 12-B2 may implement ONLY a disposable / isolated feasibility probe. Its purpose is measurement only.
- Explicitly forbidden in 12-B2: production Intraday engine; strategy evaluation; factor scoring; BUY; SELL; paper orders; executions; positions; cash ledger; business Room schema mutation; production database migration; real brokerage orders; KIS `/trading/`; production scheduler; production UI rollout.
- 12-B2 requires a separate execution instruction before work begins.

**A-2 — Probe foreground-service type: HUMAN APPROVED — 2026-10-02**

- Initial 12-B2 experiment candidate: `specialUse`.
- `specialUse` is authorized as the first probe candidate only. It is NOT the final production FGS selection; the production FGS type remains measure-first (B-10, post-B2).
- Play Console approval concerns apply only if distributed through Google Play; the current probe is a personal / ADB-installed APK workflow.
- If platform implementation evidence shows `specialUse` is invalid for the probe: STOP and report rather than silently switching type. Do NOT switch to `dataSync`, `systemExempted`, or any other type without new Human approval.

**A-3 — KIS environment / credential class: HUMAN APPROVED — 2026-10-02**

- Initial 12-B2 credential environment: VIRTUAL / demo quotation credentials.
- Reasoning boundary: the initial probe scope is Korean stocks only; `H0STCNT0` uses the same TR for real and demo [S-06], which supports the required research path in the researched environment; futures are not part of the first probe.
- Do NOT use PRODUCTION credentials in the initial probe. If an essential measurement cannot be performed using VIRTUAL: STOP, report the exact missing capability, and request separate Human approval before using PRODUCTION quotation credentials.
- B1-F13 clarification: only `H0IFCNT0` index-futures real-time has verified real-account-only evidence [S-15]; `H0CFCNT0` environment support remains UNVERIFIED [S-16]. That fact does not justify PRODUCTION credentials for the initial stock-only probe.

**A-4 — WebSocket: HUMAN APPROVED — 2026-10-02**

- 12-B2 may use the KIS quotation WebSocket.
- Primary probe purpose: observe `H0STCNT0` domestic-stock trades; evaluate Option A feasibility; measure timing / gap / reconnect / batching behaviour.
- No account / order WebSocket TRs. No trading TRs.

**A-5 — Positive read-only allowlist: HUMAN APPROVED — REQUIRED — 2026-10-02**

- 12-B2 must use a positive allowlist (§12).
- REST authorization must explicitly enumerate: the allowed KIS host / environment; the exact auth / approval paths required by the probe; the exact read-only quotation paths; the exact quotation TR IDs.
- WebSocket authorization must explicitly enumerate: the allowed KIS WebSocket endpoint; the approval-key acquisition boundary; the allowed quotation TR IDs; the allowed environment.
- No wildcard safety assumption; no "anything under `/quotations/` is safe"; no `/trading/`.
- If a request, endpoint, or TR is not allowlisted: FAIL CLOSED.

**A-6 — Probe instrument set: HUMAN APPROVED — 2026-10-02**

- Initial instrument set: `005930` (Samsung Electronics) and `000660` (SK hynix). Default first probe: 2 instruments.
- An optional third instrument may be added ONLY if required to compare liquidity behaviour, and must be explicitly named in the future 12-B2 execution instruction.
- Do not expand to tens of instruments yet; universe-cap measurement is a later group-B item (B-4).

**A-7 — Probe source classes: HUMAN APPROVED — 2026-10-02**

- Initial 12-B2 probe scope: Korean domestic stocks only.
- Included: domestic stock quotation REST where required; the domestic stock WebSocket trade stream; timing / connectivity / platform measurements.
- Excluded from the initial probe: Korean futures; USD/KRW; Treasury; overseas futures; index breadth; Theme aggregation; paid market-data subscriptions. These may be added only by later bounded authority.

**A-8 — Venue: HUMAN APPROVED — 2026-10-02**

- Initial probe venue: `J` = KRX.
- Not used in the first probe: `NX`, `UN`, the NXT stream (`H0NXCNT0`), the integrated stream (`H0UNCNT0`).
- Reason: docs/160 approved a KRX regular-session MVP, and the initial probe should minimize venue ambiguity. The production venue remains a later decision (C-11).

**A-9 — Probe data isolation / disposal: HUMAN APPROVED — REQUIRED — 2026-10-02**

- Probe evidence must be isolated from the BJStock financial / business source of truth.
- Forbidden writes: orders; executions; positions; `cash_ledger`; strategy evaluations; production factor tables; production Run lifecycle state.
- Probe data may use isolated debug / probe storage, isolated files / logs, or explicitly probe-only structures, provided they are clearly separated from the production source of truth.
- The future 12-B2 instruction must define: storage location; fields captured; retention period; export / evidence format; disposal / cleanup procedure.
- No secrets or tokens may enter probe evidence.

**A-10 — Device / user actions: HUMAN APPROVED — 2026-10-02**

- The probe is manually started by the user from a visible app screen.
- Allowed user-side actions: install the APK via ADB; open the probe screen; grant notification permission if requested; start the foreground probe manually; turn the screen OFF for measurement and ON for checkpoints; perform approved network-transition tests; inspect battery state.
- The probe must NOT silently change battery-optimization settings, system permissions, or OEM battery-manager exclusions without explicit user action.
- The device baseline may use the currently available physical Android device, but the future 12-B2 instruction must verify its actual model, OS version, build, and battery / OEM state before running (M-28).

**A-11 — Trading-day test windows: HUMAN APPROVED — 2026-10-02**

- Initial acceptance evidence target: Test Day 1 is one full normal KRX regular session (09:00–15:30 observation window); Test Day 2 is one additional normal KRX trading day for confirmation.
- The exact calendar dates are selected when the user / device is available on valid KRX trading days; no dates are set in this document.
- If a full session cannot be completed: record partial evidence, but do NOT call full-session acceptance PASS.

## 22. Recommendation / Gate Outcome

Area outcomes are aligned with the §21 groups. "GO TO 12-B2" below means "in scope for the Human-approved initial probe (§21.1)"; the probe itself starts only under a separate 12-B2 execution instruction.

| Area | Recommendation | §21 groups involved |
| --- | --- | --- |
| Korean stock intraday | GO TO 12-B2 (initial probe scope: domestic stocks, venue `J`, `005930` / `000660`, VIRTUAL) | A-3, A-6, A-7, A-8 approved; B-1 to B-3 measured |
| Option A | POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE. Not VERIFIED. Unprovable strict-after ⇒ NO FILL; same-second evidence is insufficient; a switch to Option B requires later explicit Human approval | A-4, A-5 approved; B-5 to B-7 measured first |
| Korean futures | Excluded from the initial probe; later bounded authority only. `H0IFCNT0` index-futures real-time is VERIFIED real-account-only; `H0CFCNT0` environment support remains UNVERIFIED | A-7 approved (excluded); C-5 can wait |
| USD/KRW | Excluded from the initial probe; CAN WAIT; KIS currency-futures access and intraday spot: NEEDS MORE PROVIDER RESEARCH | A-7 approved (excluded); C-2 |
| U.S. Treasury | Excluded from the initial probe; `DAILY_CONTEXT` only by later bounded authority; paid / intraday proxy: CAN WAIT | A-7 approved (excluded); C-3 |
| Overseas futures / index | Excluded from the initial probe; CAN WAIT; NEEDS MORE PROVIDER RESEARCH | A-7 approved (excluded); C-4 |
| Theme | Excluded from the initial probe; no separate acquisition; follows Korean stock intraday | A-7 approved (excluded) |
| Breadth | Excluded from the initial probe; later bounded authority only (whole-market derivation: NOT FEASIBLE AS CURRENTLY PROPOSED) | A-7 approved (excluded) |
| Android foreground session | POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE. `specialUse` selected only as the probe candidate; `systemExempted` not selected; `dataSync` 6-hour background limit remains a material concern; final runtime topology remains OPEN | A-2, A-10 approved; B-8, B-10 measured first |
| Exact alarms | DEFERRED | C-6 |
| KRX calendar / session | GO TO 12-B2 within the A-5 allowlist; special sessions: NEEDS MORE PROVIDER RESEARCH | A-11 approved |

**Gate outcome: Phase 12-B1 APPROVED / CLOSED (Human, 2026-10-02).** The research evidence and feasibility framing are accepted, and the group-A decisions A-1 to A-11 are HUMAN APPROVED (§21.1). This does not prove Option A, approve an Android-local production runtime, select a production foreground-service type, set a production universe cap, set grace / deadline / `max_age`, or decide the Android-vs-external topology. Group-B values are measured in 12-B2 before any decision; group-C items wait until after 12-B2; group-D items are fixed by docs/160. Final gate status is recorded in §24.

Fail-Closed implications (§0.2 unchanged):

- A source without official evidence stays UNVERIFIED and cannot become a required model input until verified.
- Any missing, late, or stale input (including Treasury publication delays and WebSocket gaps) is MISSING, never zero, never substituted.
- A foreground-service timeout, Task Manager stop, or Doze network loss produces MISSED / FAILED slots, never reconstructed trades.
- An Option A price that cannot be proven strictly after `order_created_at` is not a fill (NO FILL; no approximation, no backfill).

Carry-forward (from docs/160 §37, unchanged and still open):

| Finding | Status after 12-B1 |
| --- | --- |
| F-21 Fill-price provenance retention | Still open. Option A evidence would be a trade-stream record (`STCK_CNTG_HOUR`, `STCK_PRPR`, venue, receipt time); KIS does not retain stream history, so BJStock must (12-C / 12-I / 12-K) |
| F-22 Pending-fill boundary before the Risk Gate | Still open; trade-stream latency (M-03, M-07) is an input (12-C / 12-I) |
| F-23 Source of truth reopens if the external executor is chosen | Still open; depends on 12-B2 and the topology decision |
| F-24 `decision_at` / `order_created_at` in trace and UI | Still open (12-K / 12-M); the device-clock vs exchange-clock offset (M-03, M-19, M-20) should be visible alongside |

## 23. Source Register (URLs plus access date)

All external sources accessed 2026-10-02. KIS sample files are in the KIS-maintained repository `koreainvestment/open-trading-api` (branch `main`).

| ID | Source | URL | Accessed |
| --- | --- | --- | --- |
| S-01 | KIS open-trading-api README (EGW00201, demo limits) | https://github.com/koreainvestment/open-trading-api/blob/main/README.md | 2026-10-02 |
| S-02 | KIS `kis_auth.py` (token lifetime, 알림톡, sample pacing, WebSocket approval, frame parsing, reconnect loop, sample check `len(open_map.keys()) > 40` on subscribed TR-type keys, not an instrument or official limit) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/kis_auth.py | 2026-10-02 |
| S-03 | 주식당일분봉조회 `inquire_time_itemchartprice` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_itemchartprice/inquire_time_itemchartprice.py | 2026-10-02 |
| S-04 | 주식일별분봉조회 `inquire_time_dailychartprice` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py | 2026-10-02 |
| S-05 | 주식현재가 당일시간대별체결 `inquire_time_itemconclusion` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_itemconclusion/inquire_time_itemconclusion.py | 2026-10-02 |
| S-06 | 국내주식 실시간체결가(KRX) `ccnl_krx` (H0STCNT0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ccnl_krx/ccnl_krx.py | 2026-10-02 |
| S-07 | 국내휴장일조회 `chk_holiday` (TCA0903R) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/chk_holiday/chk_holiday.py | 2026-10-02 |
| S-08 | 국내선물 영업일조회 `market_time` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/market_time/market_time.py | 2026-10-02 |
| S-09 | 국내주식 장운영정보(KRX) `market_status_krx` (H0STMKO0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/market_status_krx/market_status_krx.py | 2026-10-02 |
| S-10 | 업종 분봉조회 `inquire_time_indexchartprice` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_indexchartprice/inquire_time_indexchartprice.py | 2026-10-02 |
| S-11 | 국내지수 실시간체결 `index_ccnl` (H0UPCNT0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/index_ccnl/index_ccnl.py | 2026-10-02 |
| S-12 | 선물옵션 시세 `inquire_price` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/inquire_price/inquire_price.py | 2026-10-02 |
| S-13 | 선물옵션 분봉조회 `inquire_time_fuopchartprice` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/inquire_time_fuopchartprice/inquire_time_fuopchartprice.py | 2026-10-02 |
| S-14 | 선물옵션 기간별시세 `inquire_daily_fuopchartprice` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/inquire_daily_fuopchartprice/inquire_daily_fuopchartprice.py | 2026-10-02 |
| S-15 | 지수선물 실시간체결가 (H0IFCNT0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/index_futures_realtime_conclusion/index_futures_realtime_conclusion.py | 2026-10-02 |
| S-16 | 상품선물 실시간체결가 (H0CFCNT0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/commodity_futures_realtime_conclusion/commodity_futures_realtime_conclusion.py | 2026-10-02 |
| S-17 | KIS commodity futures master loader | https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/domestic_commodity_future_code.py | 2026-10-02 |
| S-18 | KIS public commodity futures master file | https://new.real.download.dws.co.kr/common/master/fo_com_code.mst.zip | 2026-10-02 |
| S-19 | 해외선물 분봉조회 `inquire_time_futurechartprice` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_futureoption/inquire_time_futurechartprice/inquire_time_futurechartprice.py | 2026-10-02 |
| S-20 | 해외선물옵션 실시간체결가 `ccnl` (HDFFF020; paid CME / SGX) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_futureoption/ccnl/ccnl.py | 2026-10-02 |
| S-21 | 해외선물 체결추이(틱) `tick_ccnl` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_futureoption/tick_ccnl/tick_ccnl.py | 2026-10-02 |
| S-22 | 해외선물옵션 장운영시간 `market_time` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_futureoption/market_time/market_time.py | 2026-10-02 |
| S-23 | 해외주식 실시간지연체결가 `delayed_ccnl` | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/delayed_ccnl/delayed_ccnl.py | 2026-10-02 |
| S-24 | 해외지수 분봉 `inquire_time_indexchartprice` (overseas) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/inquire_time_indexchartprice/inquire_time_indexchartprice.py | 2026-10-02 |
| S-25 | KIS overseas index code loader | https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/overseas_index_code.py | 2026-10-02 |
| S-26 | KIS Developers portal (notice titles) | https://apiportal.koreainvestment.com/intro | 2026-10-02 |
| S-27 | KIS customer guide — trading hours by market | https://securities.koreainvestment.com/main/customer/guide/_static/TF04ad010000.jsp?tab=2 | 2026-10-02 |
| S-28 | KRX 휴장일 (official holiday page) | https://open.krx.co.kr/contents/MKD/01/0110/01100305/MKD01100305.jsp | 2026-10-02 |
| S-29 | KRX 유가증권시장 매매거래제도일반 | https://regulation.krx.co.kr/contents/RGL/03/03010100/RGL03010100.jsp | 2026-10-02 |
| S-30 | KRX 유가증권시장 시간외종가 / 애프터마켓 | https://regulation.krx.co.kr/contents/RGL/03/03010301/RGL03010301.jsp | 2026-10-02 |
| S-31 | U.S. Treasury Interest Rate Statistics | https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics | 2026-10-02 |
| S-32 | U.S. Treasury Yield Curve Methodology | https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics/treasury-yield-curve-methodology | 2026-10-02 |
| S-33 | Bank of Korea ECOS Open API (access denied from research network) | https://ecos.bok.or.kr/api/ | 2026-10-02 |
| S-34 | Seoul Money Brokerage 매매기준율 | http://www.smbs.biz/ExRate/StdExRate.jsp | 2026-10-02 |
| S-35 | Android — Foreground service types | https://developer.android.com/develop/background-work/services/fgs/service-types | 2026-10-02 |
| S-36 | Android — Foreground service timeout behavior | https://developer.android.com/develop/background-work/services/fgs/timeout | 2026-10-02 |
| S-37 | Android — Restrictions on starting a foreground service from the background | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start | 2026-10-02 |
| S-38 | Android — Declare foreground services and request permissions | https://developer.android.com/develop/background-work/services/fgs/declare | 2026-10-02 |
| S-39 | Android — Launch a foreground service | https://developer.android.com/develop/background-work/services/fgs/launch | 2026-10-02 |
| S-40 | Android — Handle user-initiated stopping (Task Manager) | https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping | 2026-10-02 |
| S-41 | Android — Notification runtime permission | https://developer.android.com/develop/ui/views/notifications/notification-permission | 2026-10-02 |
| S-42 | Android — Optimize for Doze and App Standby | https://developer.android.com/training/monitoring-device-state/doze-standby | 2026-10-02 |
| S-43 | Android — Power management resource limits | https://developer.android.com/topic/performance/power/power-details | 2026-10-02 |
| S-44 | Android 16 — Behavior changes: all apps | https://developer.android.com/about/versions/16/behavior-changes-all | 2026-10-02 |
| S-45 | Android 16 — Behavior changes: apps targeting Android 16 | https://developer.android.com/about/versions/16/behavior-changes-16 | 2026-10-02 |
| S-46 | Android — Use wake locks | https://developer.android.com/develop/background-work/background-tasks/awake/wakelock | 2026-10-02 |
| S-47 | Android vitals — Excessive partial wake locks | https://developer.android.com/topic/performance/vitals/excessive-wakelock | 2026-10-02 |
| S-48 | Android — Schedule alarms | https://developer.android.com/develop/background-work/services/alarms/schedule | 2026-10-02 |
| S-49 | Android 14 — Schedule exact alarms are denied by default | https://developer.android.com/about/versions/14/changes/schedule-exact-alarms | 2026-10-02 |
| S-50 | Android developer verification | https://developer.android.com/developer-verification | 2026-10-02 |
| S-51 | Play Console Help — Understanding foreground service and full-screen intent requirements | https://support.google.com/googleplay/android-developer/answer/13392821 | 2026-10-02 |
| S-52 | Android 17 — Behavior changes: all apps | https://developer.android.com/about/versions/17/behavior-changes-all | 2026-10-02 |
| S-53 | Android 17 — Behavior changes: apps targeting Android 17 | https://developer.android.com/about/versions/17/behavior-changes-17 | 2026-10-02 |
| S-54 | Android developer verification — FAQ (ADB installs, limited distribution) | https://developer.android.com/developer-verification/guides/faq | 2026-10-02 |
| S-55 | 주식현재가 체결 `inquire_ccnl` (FHKST01010300) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_ccnl/inquire_ccnl.py | 2026-10-02 |
| S-56 | 국내주식 실시간체결가 (NXT) `ccnl_nxt` (H0NXCNT0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ccnl_nxt/ccnl_nxt.py | 2026-10-02 |
| S-57 | 국내주식 실시간체결가 (통합) `ccnl_total` (H0UNCNT0) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ccnl_total/ccnl_total.py | 2026-10-02 |

Discovery-only material (news articles, third-party blogs and community posts about the KRX after-market, circulating KIS rate-limit numbers, and "41 registrations per session" reports) was used only to locate the official sources above and is not cited as evidence.

## 24. 12-B1 Final Gate Status

| Item | Status |
| --- | --- |
| Phase 12-B1 | APPROVED / CLOSED (Human, 2026-10-02) |
| Research | APPROVED |
| Independent Review | Independent Delta Re-verification: PASS — BLOCKER 0, HIGH 0, MEDIUM 0 (LOW 1, INFO 3) |
| B1-F13 (LOW) | CLOSED BY HUMAN APPROVAL FINALIZATION: futures wording narrowed to `H0IFCNT0` (VERIFIED real-account-only) and `H0CFCNT0` (environment support UNVERIFIED) in §0, §6, §11, §12, §19 M-17, §20, §21 A-3, §22 |
| B1-F14 (INFO) | Non-blocking; editorially clarified (near-duplicate discovery-only bullets in §4 merged) |
| B1-F15 (INFO) | Non-blocking; editorially clarified (`specialUse` Play review scoped to "if distributed through Google Play"; `specialUse` is a probe candidate only, not production-selected) |
| B1-F16 (INFO) | Non-blocking; editorially clarified (§15.3 absence claim relabelled "not found in reviewed official pages", not proof of universal absence) |
| Group-A decisions A-1 to A-11 | HUMAN APPROVED — 2026-10-02 (§21.1) |
| Group-B measure-first items B-1 to B-10 | OPEN until 12-B2 measurement |
| Group-C can-wait items C-1 to C-11 | OPEN (later decisions) |
| Option A | POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE; unprovable strict-after ⇒ NO FILL |
| Android local | POSSIBLY FEASIBLE — NEEDS 12-B2 PROBE; `specialUse` probe candidate only |
| 12-B2 | HUMAN AUTHORITY APPROVED; NOT STARTED |
| 12-C | NOT AUTHORIZED |

PAPER TRADING ONLY. No real brokerage order capability. KIS `/trading/` remains forbidden.
