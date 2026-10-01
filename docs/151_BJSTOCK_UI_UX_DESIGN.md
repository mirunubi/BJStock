# 151 BJStock UI/UX Design Baseline

| Item | Value |
| --- | --- |
| Document status | **DRAFT — Human Review Required** |
| Implementation | **PARTIAL** — UI-1 navigation shell IMPLEMENTED, UI-3 Home dashboard PARTIAL (first version); see §21 |
| Scope | Future MVP / real-use UI structure. |
| Phase context | Phase 11 — Documentation Interlude (not a Phase 11 gate) |

This document defines the target UI structure. It does not change Room / PostgreSQL schema or any domain / runtime invariant. Sections 1–20 are design targets for the UI phases (§15); §21 records what has been implemented.

---

## 1. Product UI identity

BJStock is **not** a brokerage / MTS clone.

Canonical product UI identity:

> **개인 투자전략 검증 및 모의투자 관제 앱**

The normal user UI answers four questions:

1. 오늘 전략이 무엇을 판단했는가?
2. 내 모의계좌가 어떻게 변했는가?
3. 전략이 실제로 성과를 내고 있는가?
4. 자동운영이 정상적으로 작동하고 있는가?

| Primary emphasis | Not primary |
| --- | --- |
| strategy decisions | order book |
| paper portfolio | real-time manual trading |
| forward-test operation | brokerage account |
| long-term performance | financial news feed |
| operational health | social / community features |

---

## 2. Language policy

- User-facing BJStock UI is **Korean only**. There is no product-level multilingual / i18n requirement.
- Internal identifiers stay English and are never translated in persisted data: source code, DB enums, `AppErrorCode`, `ForwardOutcomeReason`, `OperationalEventType`, reason codes, operation status, log keys.
- The UI maps a canonical identifier to a Korean label or message at display time only.

| Canonical identifier (persisted, English) | UI (Korean) |
| --- | --- |
| `INSUFFICIENT_WARMUP_DATA` | 전략 계산에 필요한 과거 데이터가 부족합니다. |
| `ACTIVE` (strategy version) | 사용중 |
| `SUCCEEDED` (operation) | 정상 완료 |

The canonical English identifier remains available in drill-down / diagnostics views (§10, §11, §18).

---

## 3. Primary navigation

Future bottom navigation — exactly five items:

1. 홈
2. 종목
3. 전략
4. 모의투자
5. 성과

설정 is a **top-right entry**, not a sixth bottom-navigation item.

```text
BJStock
 ├─ 홈
 ├─ 종목
 ├─ 전략
 ├─ 모의투자
 └─ 성과
      +
     설정 (top-right)
```

---

## 4. 홈 (Home)

Purpose: a **5-second** operational and investment health check.

Shown at a glance:

- total paper asset
- cumulative return
- latest strategy decision
- major holding summary
- Auto ON / OFF
- latest operation result
- next scheduled execution
- important warning

Candidate structure (illustrative values):

```text
[총 모의자산]
₩100,331,524
+0.33%

[오늘의 전략 판단]
삼성전자
HOLD
전략점수 56.32

[보유현황]
삼성전자 37주
평균단가
현재 평가금액
평가손익

[자동운영]
ON / OFF
마지막 실행
다음 실행
최근 결과

[운영 알림]
정상 / 주의 / 오류
```

Developer diagnostics must not dominate Home. Whether Home aggregates all active Runs or highlights one is open (§19).

---

## 5. 종목 (Stocks)

Purpose: stock analysis through BJStock's own strategy model.

Includes:

- KOSPI / KOSDAQ search
- symbol / name
- price
- daily chart
- factor scores
- latest strategy evaluation
- theme membership

Candidate detail:

```text
삼성전자 005930

가격 / 일봉 차트

전략점수
56.32

팩터
- PRICE_VS_MA20
- PRICE_VS_MA60
- MOMENTUM_20D
- MOMENTUM_60D
- VOLATILITY_20D
- VOLUME_RATIO_20D

테마
- HBM관련
- 반도체
```

Factor identifiers are canonical English codes. Korean display names for factors are a UI-2 terminology task (§15); the codes stay available in detail views.

Excluded: brokerage-style order book, real BUY / SELL buttons.

Themes and watchlists follow `docs/145_THEMES_AND_WATCHLISTS.md`.

---

## 6. 전략 (Strategy)

Purpose: create and manage strategy / version definitions.

```text
전략 목록
 -> 전략 상세
 -> Version
 -> Factor Weights
 -> Thresholds
 -> Signal Rules
 -> Lifecycle
```

Lifecycle labels (display only; persisted values stay English):

| Persisted `StrategyVersionStatus` | UI |
| --- | --- |
| `DRAFT` | 작성중 |
| `ACTIVE` | 사용중 |
| `RETIRED` | 종료 |

The UI must make immutability obvious: only a `DRAFT` version is editable; `ACTIVE` and `RETIRED` versions are read-only (edit controls absent or disabled with a Korean explanation, and a clear path to "copy to new draft"). Signal rule semantics follow `docs/146_SIGNAL_RULES.md`. Strategy activation is a high-impact action (§17).

The exact Strategy detail navigation structure is open (§19).

---

## 7. 모의투자 (Paper Trading)

The operational core. Current Forward Test functionality is unified here.

Candidate contents:

| Block | Contents |
| --- | --- |
| Run summary | run name, strategy / version, status, initial capital, current cash, total paper asset, return |
| Positions | instrument, quantity, average price, reference / current price, unrealized P/L |
| Orders / executions | signal date, order state, execution date, fill price, quantity |
| Auto | ON / OFF, latest execution, next execution, latest result |

Suggested internal tabs:

```text
보유 | 주문·체결 | 실행기록
```

Forward Test semantics (next-trading-day-open fills, cycles, Auto schedule, 18:00 Asia/Seoul cutoff) are defined in `docs/140_FORWARD_TEST_ORCHESTRATION.md` and `docs/141_FORWARD_TEST_OPERATIONS.md`; this UI only presents them. Presentation of multiple simultaneous paper Runs and exact Auto ON / OFF placement are open (§19).

---

## 8. 성과 (Performance)

Primary question:

> **이 전략이 실제로 쓸 만했는가?**

Uses existing analytics (no new metric definitions):

- cumulative return
- daily return
- monthly return
- MDD
- win rate
- average return
- average holding days
- Equity Curve
- monthly performance
- strategy / Run comparison

This is not a generic brokerage profit screen: every figure is framed as evidence about a strategy / Run, with the strategy version visible.

---

## 9. 설정 (Settings)

Candidate structure:

```text
설정
 ├─ KIS 연결
 ├─ 자동 실행 설정
 ├─ 운영 로그
 ├─ API 오류
 ├─ 데이터 상태
 ├─ DB 정보
 └─ 앱 정보
```

Developer-oriented screens move out of primary navigation into Settings. KIS connection UI never displays App Key / App Secret / access token values. API error presentation follows `docs/148_API_ERROR_LOGGING.md`.

---

## 10. Operational log UX

Raw `OperationalEventType` values stay internal:

```text
OPERATION_STARTED
MARKET_SYNC_RESULT
RUN_RESULT
CYCLE_STARTED
CYCLE_FINISHED
OPERATION_FINISHED
```

Normal UI shows a concise Korean summary per operation:

```text
9/30 07:34 자동 실행 완료

시장데이터 갱신    정상
전략 Run 1        완료
전략 Run 3        완료
소요시간          1.8초
```

Drill-down may expose:

- `operation_id`
- trigger (`MANUAL` / `WORKER`)
- `operation_kind`
- `throughDate`
- canonical error code
- retry state

Raw technical logs must not dominate normal UI. The event model and its persistence are defined in `docs/150_OPERATIONAL_RELIABILITY_STANDARD.md`; the UI does not add, rename, or reinterpret events. Whether operations are normally exposed or Settings-only is open (§19).

---

## 11. Error UX

Canonical code and user message are separate. The code (`AppErrorCode` or `ForwardOutcomeReason`) is persisted and shown only in drill-down; the Korean message is display-only.

| Canonical code | UI message |
| --- | --- |
| `PRIOR_RUN_BLOCKED` | 앞선 전략 실행이 중단되어 이 전략은 실행하지 않았습니다. |
| `ALREADY_RUNNING` | 다른 자동/수동 작업이 이미 실행 중입니다. |

Never shown to normal users:

- stack trace
- raw `Throwable.message`
- raw HTTP body
- token
- account secret
- uncontrolled provider message

Financial-integrity codes (`LEDGER_MISMATCH`, `EXECUTION_IDEMPOTENCY_CONFLICT`, `FILLED_ORDER_WITHOUT_EXECUTION`; severity `FINANCIAL_INTEGRITY`, `docs/150` §20.7) must be presented as a prominent error requiring user attention, never as a transient toast. Their classification is owned by `docs/150`.

---

## 12. Visual direction

BJStock should feel like **"개인 투자전략 관제판"**, not **"증권사 모바일 트레이딩 앱"**.

Principles:

- clean
- information-dense but not cluttered
- dashboard-oriented
- major numbers / status easy to read
- Korean labels first
- warnings visually obvious
- status must not rely on color alone
- avoid unnecessary financial-app visual noise

Exact colors / theme, chart library, and dark mode policy are open (§19).

---

## 13. Existing screen → future UI mapping

Conceptual migration only. **Not implemented now.**

Current inventory (NavHost routes in `MainActivity.kt`, start destination `dashboard`):

| Current screen / area | Route | Future location |
| --- | --- | --- |
| Dashboard (launcher menu + status cards) | `dashboard` | 홈 (replaced by the Home dashboard, §4) |
| Market Data Test | `market_data_test` | 종목 |
| Themes | `themes` | 종목 > 테마 |
| Strategy Lab | `strategy_lab` | 전략 |
| Forward Test | `forward_test` | 모의투자 |
| Analytics (inside Forward Test: summary metrics, Equity Curve) | part of `forward_test` | 성과 |
| Compare Runs | `compare_runs` | 성과 (strategy / Run comparison) — proposed |
| API Errors (recent errors section inside Database Info) | part of `database_info` | 설정 > API 오류 |
| Database Info | `database_info` | 설정 > DB 정보 |
| KIS Settings | `kis_settings` | 설정 > KIS 연결 — proposed |
| Forward Operations / Operational Events (no dedicated screen today; results appear as operation messages in Forward Test) | — | 모의투자 > 실행기록 and / or 설정 > 운영 로그 |
| Instrument Master | `instrument_master` | open — candidate 종목 (search source) or 설정 > 데이터 상태 |
| Factor Test | `factor_test` | open — candidate 종목 (factor scores) or developer diagnostics |
| Paper Trading Lab | `paper_lab` | open — candidate developer diagnostics |
| AI Advisor (currently OFF) | `ai_advisor` | open — not part of the five MVP sections |

Rows marked "proposed" or "open" are not decisions; see §19.

---

## 14. MVP boundary

| MVP normal UI | Developer diagnostics (Settings / drill-down) | Future / not current scope |
| --- | --- | --- |
| Korean | event codes | real brokerage trading |
| 5 primary sections | DB details | order book |
| strategy-focused | API error details | real broker positions |
| paper-trading-focused | internal identifiers | community / social |
| performance-focused | | market-news portal |
| operational status summarized | | multilingual BJStock UI |

---

## 15. Future UI implementation phases

No dates are assigned.

| Phase | Scope | Status |
| --- | --- | --- |
| UI-0 | Current screen / navigation inventory | done (§13) |
| UI-1 | Navigation shell: 홈 / 종목 / 전략 / 모의투자 / 성과 | **IMPLEMENTED** (§21) |
| UI-2 | Korean terminology cleanup | |
| UI-3 | Home dashboard | **PARTIAL** — first version (§21) |
| UI-4 | Stocks + Themes consolidation | **PARTIAL** — Stocks screen (§21.4); stock-level strategy evaluation deferred |
| UI-5 | Strategy UX consolidation | |
| UI-6 | Paper Trading / Forward Test UX consolidation | |
| UI-7 | Performance dashboard | |
| UI-8 | Settings + operational diagnostics | |
| UI-9 | Physical-device usability review | |

Each UI phase is presentation-only unless a separate, explicitly approved gate says otherwise. None of them may change Auto / Worker scheduling, the 18:00 cutoff, strategy / factor / paper-trading math, or persisted identifiers.

---

## 16. Outdoor / low-visibility requirements

Explicit design requirement: the app may be used outdoors or where screen visibility is poor.

- Important state uses **text + icon**, never color only.
- High contrast.
- Practical minimum font size.
- Large typography for major values (total asset, return, decision).
- Sufficient touch targets.
- Destructive actions visually separated from routine actions.
- Current Run / status always obvious.
- Warnings highly visible.
- Normal screens avoid dense developer tables.

Exact numeric thresholds (font sizes, contrast ratios, target sizes) are set in UI-2 / UI-9 and verified on a physical device.

---

## 17. Safety / high-impact action UX

Future UI safety principles. **No implementation yet.**

High-impact actions:

- Strategy activation
- Auto ON / OFF
- Retry failed cycle
- Run readiness transition
- future destructive / reset actions

Each must have:

- a clear Korean label
- the target Run / Strategy visibly named
- no ambiguous icon-only action
- confirmation where the impact is material
- a safe disabled state when the action is invalid (with a Korean reason)

Existing domain guards (single-flight, lifecycle immutability, run state checks) remain the source of truth; the UI never bypasses them and never offers an action the domain would reject as if it were valid.

---

## 18. Operational status model

Normal UI translates internal states; the canonical English code stays available in drill-down.

| `ForwardOperationStatus` | UI |
| --- | --- |
| `RUNNING` | 실행중 |
| `SUCCEEDED` | 정상 완료 |
| `PARTIAL` | 일부 처리 |
| `BLOCKED` | 실행 중단 |
| `FAILED` | 오류 |
| `NO_OP` | 처리할 항목 없음 |

Korean labels for other internal states (`RunStatus`, `ForwardRunResult`, order status, decision `BUY` / `HOLD` / `SELL` / `NO_ACTION`) are defined in UI-2; they follow the same rule (display-only translation, persisted value unchanged).

---

## 19. Open design questions

Left **OPEN** — no final answers in this document:

- exact colors / theme
- exact chart library
- whether Home aggregates all active Runs or highlights one
- exact Auto ON / OFF placement
- Strategy detail navigation structure
- multiple simultaneous paper Run presentation
- whether Operations is normally exposed or Settings-only
- foldable / tablet layout
- dark mode policy
- final location of screens not covered by the canonical mapping (Instrument Master, Factor Test, Paper Trading Lab, AI Advisor) and confirmation of the proposed Compare Runs / KIS Settings locations (§13)

---

## 20. Document relationships

This UI document **consumes** the following domain / runtime capabilities. It references them and does not redefine or change their invariants:

- `docs/140_FORWARD_TEST_ORCHESTRATION.md`
- `docs/141_FORWARD_TEST_OPERATIONS.md`
- `docs/145_THEMES_AND_WATCHLISTS.md`
- `docs/146_SIGNAL_RULES.md`
- `docs/147_TRADE_AUDIT_LOG.md`
- `docs/148_API_ERROR_LOGGING.md`
- `docs/150_OPERATIONAL_RELIABILITY_STANDARD.md`

If a UI need appears to require a domain change, it is raised as a separate gate; this document does not authorize it.

---

## 21. Implementation notes

### 21.1 UI-1 navigation shell (IMPLEMENTED)

- Start destination `home`. Bottom navigation: 홈 / 종목 / 전략 / 모의투자 / 성과 (`PrimaryTab`, Korean label + icon). Tab switches pop to Home with saved state and `launchSingleTop`, so tabs never stack; back from a tab returns to Home, back on Home leaves the app.
- 설정 is the top-right gear on every tab root, not a sixth tab.
- 종목 / 전략 / 모의투자 / 성과 are temporary shells that link to the existing screens (종목: 시세 조회, 테마, 종목 마스터, 팩터 점수; 전략: Strategy Lab; 모의투자: Forward Test with Run / Auto / Run Now, Paper Lab; 성과: Forward Test analytics, Compare Runs). Full redesigns stay with UI-4 – UI-7. 종목 has been replaced by the Stocks screen (§21.4).
- Icons are local Material path vectors (`BJStockIcons`); no dependency was added.

### 21.2 Developer screen preservation

No screen was removed and every pre-UI-1 route string is unchanged. 설정 lists 연결 및 데이터 (KIS 연결, DB 정보 · API 오류) and 개발자 도구 (the former start screen `dashboard`, Forward Test, Compare Runs, Strategy Lab, Paper Lab, Factor Test, Instrument Master, Market Data Test, Themes, AI Advisor). AI Advisor is reachable only there and stays OFF.

### 21.3 UI-3 Home (PARTIAL — first version)

Cards: 모의자산 (total paper asset, cumulative return / profit), 최근 전략 판단, 보유현황 (count, top three by market value), 자동운영 (ON / OFF, next run, latest operation), 운영 경고 (one line "운영 상태 정상" when there is nothing to report). Loading, error ("홈 정보를 불러오지 못했습니다…"), no Run ("실행 중인 모의투자가 없습니다"), no decision, and no holdings states are explicit. Data comes only from existing read APIs (`PerformanceAnalyticsService.calculateSummary` / `loadOpenPositionViews`, `PerformanceAnalyticsRepository`, `ForwardTestScheduler.status`, `ForwardOperationDao.findRecent`); Home computes no returns, scores, positions, or cash. Before the first valuation the card shows the Run's initial capital labelled "초기 자본 (아직 평가 전)". The next run is written as "<date> 오전 7:00 이후" (07:00 KST earliest eligible target, `docs/060` D-168), never as an exact time. Canonical statuses and decisions are translated (§18, `KoreanLabels`); the decision shows the canonical value as small secondary text. Error and warning text is fixed Korean; `Throwable.message`, summary error text, and raw codes are not shown.

**Temporary Run selection rule (open for human review, §19):** Home shows one Run. DRAFT and CANCELLED Runs are excluded; the rest are ordered RUNNING, READY, PAUSED, COMPLETED, then by highest `strategy_runs.id` (most recently created). If other candidates exist, Home notes their count and points to 모의투자. The latest decision is the Run's last `stock_evaluations` row by (`evaluation_date`, `id`), with a count of other decisions on the same date. The latest operation is the newest `forward_operations` row (`started_at`, `id`), across all Runs.

### 21.4 UI-4 Stocks + Themes consolidation (PARTIAL — Stocks screen)

The 종목 tab is a stock-analysis screen (`StocksScreen`, `StocksViewModel`, `StocksPresenter`, read-only `StocksDataSource`) inside the normal chrome (title 종목, settings gear, bottom bar with 종목 selected). It is not an order screen: no order book, order buttons, or account actions.

- **Search**: "종목명 또는 종목코드 검색" over active instruments only (`InstrumentDao.searchActive`, symbol or name, partial match; inactive instruments never appear). Rows show name and `005930 · KOSPI` / `KOSDAQ`; no database ids. Searching, no-result, and failure states are explicit.
- **Initial state**: guidance "종목명이나 종목코드를 검색해 보세요.", active themes as quick-access chips, and 테마 관리. No stock is preselected and no recent-stock history is invented.
- **Theme browsing (IMPLEMENTED)**: a theme chip lists its active member instruments (`ThemeService.listInstrumentIds`); selecting one opens the same stock detail. 테마 관리 opens the existing Themes screen, where theme CRUD stays.
- **Stock detail**: name, `code · board`, and sector / industry only when stored. 저장 일봉 기준 card: latest stored close, trade date (`2026.09.29`), volume (`주`), and 전일 대비 as amount and signed percent against the previous stored trading close (display arithmetic only, never persisted; "—" without a previous bar; "저장된 시세 데이터가 없습니다." without bars). Direction is written as text (+ / − and 상승 / 하락 / 보합); color is secondary.
- **Chart**: Compose Canvas line of up to the latest 30 stored closes in date order, straight segments between real bars, latest close marked, start / end dates and high / low labels. No chart dependency, candlesticks, zoom, or indicators.
- **Factors**: the six system factors with Korean names (20일 이동평균 대비, 60일 이동평균 대비, 20일 모멘텀, 60일 모멘텀, 20일 변동성, 20일 거래량 비율), the code as small secondary text, raw value, and normalized score. Calculated by the existing `FactorCalculationService.calculateAllSystemFactors` with **`persist = false`** as of the **latest stored trade date**, so browsing never writes `factor_values`. Insufficient history shows "계산에 필요한 과거 데이터가 부족합니다." instead of a value; scores are never turned into recommendations. (The service's existing idempotent seeding of the six system factor definitions still applies; it inserts only when a definition is missing.)
- **Themes**: active themes containing the stock (`ThemeService`), plus 테마 관리.
- **현재가 조회 (manual only)**: KIS is called only when the user taps the button (`KisMarketRepository.inquireCurrentPrice`, read-only); never on opening the tab, searching, or selecting. The result is labelled "현재가 조회 결과" with the lookup time, separate from stored data, and not called real-time. Failures show only the existing safe public message ("현재가 조회 실패 · 인증 필요" etc.) or a fixed Korean fallback.
- **Back**: the stock detail returns to the theme list or search results, which return to the Stocks root; tab switching is unchanged (§21.1).

Deferred: the latest strategy evaluation for a stock. No cross-Run "latest decision" rule is invented; stock-level evaluation waits for the Strategy / Paper Trading UI to define the Run context. Home keeps showing the selected Run's latest decision (§21.3).

Existing developer screens (Market Data Test, Instrument Master, Themes, Factor Test) are unchanged and stay under 설정 > 개발자 도구; the Stocks tab no longer links to them.
