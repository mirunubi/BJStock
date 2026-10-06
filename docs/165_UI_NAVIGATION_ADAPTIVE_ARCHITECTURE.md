# 165 UI Navigation / Adaptive Architecture

| Item | Value |
| --- | --- |
| Document status | **NAV-1 IMPLEMENTED ON FEATURE BRANCH** |
| Merge status | **NOT MERGED TO MAIN** (`feature/ui-strategy-admin-demo`) |
| Physical UI | **NOT YET VERIFIED** |
| Scope | Navigation chrome only: drawer, bottom bar, rail, top-level top bar. Home content adaptation (HOME-ADAPT-02): §L. |
| Related | docs/151 (UI/UX baseline, §3 navigation) |

This document records the NAV-1 adaptive navigation shell and its human-approved decisions. It does not change Room / PostgreSQL schema, runtime, providers, scheduler, or strategy engine. The original NAV-1 scope did not change any screen's content; later, separately approved adaptive screen-layout sections may extend content layout. HOME-ADAPT-02 (§L) changes the Home layout only; Home data and business semantics remain unchanged.

---

## A. Purpose

1. **Narrow-screen cleanup.** Five bottom tabs plus a Settings gear and a deep Settings › 개발자 도구 list crowded the phone layout. Compact screens now show three bottom tabs and move the full IA into a hamburger drawer.
2. **Foundation for Fold / tablet.** Medium and Expanded widths show a navigation rail instead of a bottom bar, so later gates can add adaptive content (2-column, list-detail) without reworking navigation again.

## B. Human decisions (HD-NAV-01 – HD-NAV-14)

| ID | Decision |
| --- | --- |
| HD-NAV-01 | Exactly **one NavHost call site**. The same NavController / NavHost stays alive while only the chrome around it changes. No `if (compact) NavHost(...) else NavHost(...)`. |
| HD-NAV-02 | `PrimaryTab` keeps **five** destinations: HOME, STOCKS, STRATEGY, PAPER_TRADING, PERFORMANCE. A separate bottom subset holds HOME, STRATEGY, PAPER_TRADING. |
| HD-NAV-03 | Compact bottom navigation is exactly **홈 / 전략 / 모의투자**. |
| HD-NAV-04 | Compact top-level navigation = `ModalNavigationDrawer` + hamburger + 3-item bottom bar. Drawer IA in §C. |
| HD-NAV-05 | Developer Tools is a **second depth inside the drawer**, using existing routes; no replacement screens. |
| HD-NAV-06 | The KIS item keeps the existing label **"KIS 연결"**. |
| HD-NAV-07 | Top-level compact screens show **hamburger + title**. The Settings gear is removed from the shared top-level `TabTopBar`; Settings is reached from the drawer / rail. |
| HD-NAV-08 | Subordinate / detail screens do **not** expose the drawer and keep back-arrow semantics. Top-level root state → hamburger; subordinate / detail state → back arrow. **Drawer prohibition applies to subordinate/detail state at all width classes.** |
| HD-NAV-09 | Medium and Expanded use a **NavigationRail**, no bottom bar. Rail IA in §D. |
| HD-NAV-10 | No "More" overflow for primary destinations on the rail. |
| HD-NAV-11 | `database_info` and the legacy `dashboard` stay as existing routes but are **not promoted** into the drawer / rail IA. |
| HD-NAV-12 | Settings developer links remain as **alternate entry points** in NAV-1. |
| HD-NAV-13 | NAV-1 changes navigation chrome only. All screen content stays single-column; width changes never change business state. |
| HD-NAV-14 | The Medium / Expanded NavigationRail **may remain visible on subordinate screens**: it is global wide-screen navigation chrome, not the same interaction surface as the Compact hamburger drawer. HD-NAV-08 stays unchanged and global: subordinate / detail screens must not expose or open the drawer at any width. |

## C. Compact IA (width < 600dp)

Bottom bar (`BottomNav.tabs`): **홈 / 전략 / 모의투자**.

Drawer (`DrawerMenu.groups`), opened only from the hamburger of a top-level root screen:

```
주요
  홈            -> navigateToTab(HOME)
  종목          -> navigateToTab(STOCKS)
  전략          -> navigateToTab(STRATEGY)
  모의투자      -> navigateToTab(PAPER_TRADING)
  성과          -> navigateToTab(PERFORMANCE)
운영
  운영 · 감사   -> open(admin)
앱
  설정          -> open(settings)
  개발자도구 >  -> drawer page DEV_TOOLS (no route)
```

Selecting a destination closes the drawer, then navigates with the existing helpers (`navigateToTab` for primary destinations, `open` = `navigate { launchSingleTop = true }` for pushes). There are no parallel routing semantics.

When 종목 or 성과 is entered from the drawer, the screen is a normal top-level destination; its bottom bar stays visible with **no item selected** (`BottomNav.selectedItem` returns null), so 홈 is never falsely highlighted.

## D. Medium / Expanded IA (width ≥ 600dp)

No bottom bar. The rail (`RailMenu.sections`) shows, top to bottom, with dividers between sections:

```
홈 / 종목 / 전략 / 모의투자 / 성과
---
운영 · 감사
---
설정 / 개발자도구
```

Rail rule (HD-NAV-14 with HD-NAV-08): **NavigationRail may remain visible on subordinate screens, but any Rail action that would require the ModalNavigationDrawer is unavailable while the current screen/layer is subordinate.**

- On a drawer-capable top-level root (see §H), the rail's 개발자도구 item opens the same modal drawer directly on its Developer Tools page, so the grouping is identical to Compact.
- On any other state (Settings, 운영 · 감사, legacy tools, `database_info`, `dashboard`, Stocks detail / theme browse, Strategy detail / version / template preview, 성과 모의투자 비교), the 개발자도구 item is shown **disabled**. It is disabled rather than hidden: the rail keeps a stable layout and the result is deterministic. All other rail items stay enabled. Settings › 개발자 도구 remains the alternate path.

Top-level top bars show the title only (no hamburger, no gear). The rail column scrolls if the window is short (e.g. a phone in landscape); this is not a "More" overflow. The rail pads the start inset itself and the content area consumes it, so screens do not apply the start inset twice.

## E. Single NavHost invariant

```
navigation width class (NavWidthClass)
        |
BJStockAdaptiveShell
  ├ ModalNavigationDrawer (always present; opened by hamburger or rail 개발자도구, drawer-capable state only)
  ├ Row
  │   ├ NavigationRail        (Medium / Expanded only)
  │   └ Box(weight 1)
  │        └ child back dispatcher (enabled only while the drawer is closed)
  │             └ content()  ← the single NavHost from MainActivity.BJStockNavHost
  └ drawer BackHandler (enabled only while the drawer is open)
```

- `MainActivity.BJStockNavHost` holds the only `rememberNavController()` and the only `NavHost(...)` call; the graph is registered once.
- The shell calls `content()` from one fixed position. The rail is a conditional sibling before it, so a width change (including Activity recreation on fold / rotate) keeps the NavHost, back stack, and back-stack-entry ViewModels.
- Screens keep calling `TabTopBar` / `BJStockBottomBar`; the shell provides `LocalNavChrome` (width class + drawer opener), so screens need no width logic.
- A source test (`NavigationShellInvariantTest`) asserts there is exactly one `NavHost(` call in `app/src/main`.

## F. PrimaryTab (5) vs bottom subset (3)

| Concept | Members | Used by |
| --- | --- | --- |
| `PrimaryTab` (top-level destinations) | HOME, STOCKS, STRATEGY, PAPER_TRADING, PERFORMANCE | NavHost routes, `navigateToTab`, drawer 주요, rail primary section, screen titles |
| `BottomNav.tabs` (compact bottom bar) | HOME, STRATEGY, PAPER_TRADING | `BJStockBottomBar` only |

종목 and 성과 remain true top-level destinations with the same tab back-stack behaviour (`navigateToTab`: back returns to 홈, back on 홈 leaves the app).

## G. Drawer second-depth Developer Tools

`DrawerMenu.developerTools` (all existing routes, unchanged screens):

| Label | Route |
| --- | --- |
| KIS 연결 | `kis_settings` |
| 테마 관리 | `themes` |
| 종목 마스터 | `instrument_master` |
| Market Data Test | `market_data_test` |
| Factor Test | `factor_test` |
| Strategy Lab | `strategy_lab` |
| Paper Lab | `paper_lab` |
| Forward Test | `forward_test` |
| Run 비교 | `compare_runs` |
| AI Advisor | `ai_advisor` |

The group is a drawer page (`DrawerPage.DEV_TOOLS`), not a NavHost destination. Its header back arrow returns to the drawer root. Closing the drawer always resets the page to the root (`DrawerNavigation.pageAfterClose`), so the next open is deterministic.

### Back priority (every width; the drawer can also be open on Medium / Expanded through rail 개발자도구)

| # | State | Back does |
| --- | --- | --- |
| 1 | Drawer open on Developer Tools | return to drawer root |
| 2 | Drawer open on root | close drawer |
| 3 | Screen in-screen layer open (Stocks detail / theme browse, Strategy strategy / version / template preview, 성과 모의투자 비교, Admin detail) | the screen's layer back handler closes the layer (`LayerBackHandler` in Stocks / Strategy / 성과, the existing `BackHandler` in Admin) |
| 4 | Pushed subordinate destination | `popBackStack()` |
| 5 | Top-level destination | existing tab behaviour (to 홈, then leave the app) |

Material3 1.4.0's `ModalNavigationDrawer` registers no back handler itself, and the shell uses the `ModalDrawerSheet` overload without `drawerState`, so the shell's drawer handler is the only drawer handler.

### Back priority invariant

- **Drawer priority is state-driven.** `BackPriority` (in `BJStockDrawer.kt`) expresses the table above as state; a level wins because every lower level is disabled, never because its callback was registered later.
- **The NavHost subtree is disabled while the drawer is open.** The shell wraps its `content()` (the single NavHost and every screen) in a child back dispatcher, `rememberNavigationEventDispatcherOwner(enabled = BackPriority.contentBackEnabled(drawerOpen))`, provided through `LocalNavigationEventDispatcherOwner` (navigationevent-compose 1.0.0, already on the classpath through activity-compose 1.13; no dependency added). This also covers NavHost's own internal back handler, which the app cannot gate directly. The drawer handler lives on the Activity dispatcher with `enabled = BackPriority.drawerHandlerEnabled(drawerOpen)`.
- **Screen-level BackHandlers are disabled while the drawer is open.** Stocks, Strategy, and 성과 use `LayerBackHandler`, enabled by `BackPriority.layerHandlerEnabled(hasInScreenLayer, drawerOpen)`, reading the read-only `drawerOpen` from `LocalNavChrome`. Screens never see the mutable `DrawerState`. Admin's detail handler is unchanged: Admin is never drawer-capable, and the disabled subtree covers it anyway.
- **The drawer closes automatically if `DrawerAccess` becomes false** while it is open (`DrawerAccess.mustClose`): an in-screen layer opens, or the route changes to Settings, 운영 · 감사, or a legacy screen.
- **No lifecycle or callback-registration-order dependency is required.** There is no lifecycle-keyed re-registration; `BackPriorityDispatchTest` verifies on the real dispatcher that the drawer wins whether it registered before or after NavHost and screen handlers. Within the enabled NavHost subtree, a screen layer handler taking precedence over NavHost's pop (rows 3 vs 4–5) is the standard Navigation-Compose contract and is unchanged by NAV-1.

## H. Subordinate-screen drawer prohibition

Drawer prohibition applies to subordinate/detail state at all width classes (HD-NAV-08).

- **Drawer-capable state** is defined in one place, `DrawerAccess.isDrawerCapable(route, hasInScreenLayer)`: the current route is a `PrimaryTab` route **and** that screen has no in-screen layer open.
- The in-screen-layer signal has a single source: the `onBack` that Stocks, Strategy, and 성과 pass to `TabTopBar` only while a layer is open (the same value that makes the back arrow win over the hamburger). `TabTopBar` reports it to the shell keyed by its back-stack entry, so a screen transition cannot overwrite another entry's state.
- 성과 모의투자 비교 (`comparison.open`) is an in-screen layer (approved HD-PN-01, HD-PN-02; PERF-NAV-02). While it is open, system Back and the top-bar back arrow both call the existing `closeComparison()` and stay on 성과; the next Back follows the existing top-level behaviour. The title stays 성과, and the in-content 닫기 button and the comparison UI are unchanged. Closing resets only the comparison picks, message, and result; the selected Run and its detail are kept.
- The shell's single `openDrawer` refuses to open the drawer unless the current state is drawer-capable, so no path (hamburger or rail) can open the drawer over a subordinate or detail screen.
- The hamburger exists only in `TabTopBar`, which only the five top-level screens (and the unreachable TabHub) use.
- `TabTopBar` resolves its leading icon with `TopBarNavIcon.resolve`: an in-screen layer's back arrow always wins over the hamburger; the two are never shown together, and the drawer cannot be opened from a detail layer.
- Settings, 운영 · 감사, and the legacy tools use `BackTopBar` or their own top bars with a back arrow; they never show a hamburger.
- Drawer swipe gestures are enabled only while the drawer is open (`DrawerNavigation.gesturesEnabled`), so an edge swipe can never open the drawer over a subordinate or detail screen.

## I. Deferred work (NAV-CLEANUP)

| ID | Deferred item | Where |
| --- | --- | --- |
| NAV-CLEANUP-01 | `database_info` not promoted into the drawer / rail IA; still reachable from Settings and the legacy dashboard. | `BJStockDestinations.kt` (`DrawerMenu.developerTools`) |
| NAV-CLEANUP-02 | Legacy `dashboard` not promoted into the drawer / rail IA; still reachable from Settings › 개발자 도구. | `BJStockDestinations.kt` (`DrawerMenu.developerTools`) |
| NAV-CLEANUP-03 | Settings keeps duplicate developer-tool entry paths next to the drawer / rail group. | `BJStockDestinations.kt` (`SettingsMenu`) |
| NAV-CLEANUP-04 | Unreachable `TabHubs` / `TabHubScreen` retained. | `BJStockDestinations.kt` (`TabHubs`) |
| NAV-CLEANUP-05 | Legacy developer screens keep private `TopAppBar`s (not unified with `BackTopBar`). | `BJStockChrome.kt` (`BackTopBar`) |
| NAV-CLEANUP-06 | Material3 Adaptive / WindowSizeClass dependency deferred; NAV-1 uses local 600 / 840dp breakpoints. | `NavWidthClass.kt` |
| NAV-CLEANUP-07 | Home adaptive 2-column layout. **Resolved on the feature branch by HOME-ADAPT-02** (§L). | `feature/home/HomeLayout.kt` |
| NAV-CLEANUP-08 | Strategy List-Detail. **Resolved on the feature branch by STRATEGY-ADAPT-02** (§M). | `feature/strategy/StrategyLayout.kt` |
| NAV-CLEANUP-09 | Admin adaptive layout. **Resolved on the feature branch by ADMIN-ADAPT-02** (§N). | `feature/admin/AdminLayout.kt` |
| NAV-CLEANUP-10 | Fold posture (hinge / tabletop) handling deferred; needs androidx.window on the compile classpath. | docs only |

## J. Search instruction

Every deliberate NAV-1 cleanup decision carries the marker `NAV-CLEANUP`. To list all of them:

```
rg "NAV-CLEANUP" app docs
```

Remove a marker only in the gate that resolves that cleanup item.

## K. Scope boundary

- No DB changes (Room version stays 12), no migration.
- No runtime, provider, scheduler, or strategy-engine changes.
- No business logic, persistence, or new dependency.
- Screen content unchanged; the five top-level screens and TabHub lost only the now-unused `onOpenSettings` parameter (gear removed per HD-NAV-07).

## L. Home adaptive layout (HOME-ADAPT-02)

HD-NAV-13 ("All screen content stays single-column") and §E ("screens need no width logic") describe NAV-1's scope. HOME-ADAPT-02 is the first gate after NAV-1 to adapt screen content, and only for Home:

| Width class | Home layout |
| --- | --- |
| Compact | Phase 3 single column, unchanged |
| Medium | Phase 3 single column, unchanged (two columns would be about 205dp inside each card at 600dp) |
| Expanded | Two columns |

| Expanded column | Sections (canonical order kept inside each column) |
| --- | --- |
| Left — 계좌 / 거래 | 모의자산 · 실행 중 전략 · 보유현황 · 최근 주문 · 체결 |
| Right — 판단 / 운영 | 최근 전략 판단 · 최근 처리일 신호 · 자동운영 · 운영 경고 · 최근 오류 · 최근 Audit |

- `HomeLayout` (pure) owns the slot order, the two groups, and `modeFor(NavWidthClass)`. Home reads the width class from `LocalNavChrome`, the same value the shell uses, so there is no second breakpoint system.
- One outer vertical scroll holds a `Row` of two top-aligned, equally weighted columns: no cross-column row-height coupling, no independent scroll regions. Each column is a semantics traversal group, so TalkBack reads the left group, then the right.
- Same `HomeViewModel` / `HomeActivityViewModel` state, same section composables, same links and retries; presenters, read models, ViewModels, queries, and DAOs are unchanged.
- Core failure (approved M-1 decision): the left 모의자산 card keeps the global message, the disclosure, and the only core retry. Because 최근 전략 판단 / 자동운영 / 운영 경고 are then missing from the right column, it repeats the existing `HomePresenter.CORE_SECTIONS_UNAVAILABLE` line once at its top: no second retry button, no placeholder cards, no "normal" wording. 최근 처리일 신호 / 최근 오류 / 최근 Audit keep rendering.
- Physical / rendered validation of the Expanded layout is deferred with the rest of the feature branch (after B2 Smoke).

## M. Strategy adaptive layout (STRATEGY-ADAPT-02)

The second separately-approved content gate after NAV-1, for the 전략 tab only:

| Width class | Strategy layout |
| --- | --- |
| Compact | Single pane, unchanged: one scroll; root shows 전략 템플릿 (미리보기) then 전략 목록; an open layer replaces the root |
| Medium | Same as Compact (beside the rail, a 360dp master would leave about 110dp for the detail at 600dp) |
| Expanded | List-detail: 360dp master pane, 16dp gap, detail pane fills the rest |

- `StrategyLayout` (pure) owns `modeFor(NavWidthClass)`, the detail resolver, the master highlight, and the empty-detail text. The screen reads the width class from `LocalNavChrome`; no breakpoint values live in `feature/strategy`.
- Expanded master: 전략 템플릿 (미리보기) above 전략 목록. The two lists stay separate; the preview keeps its demo frame, 미리보기 / 예시 / 미저장 badges, demo banner, and boundary note.
- Expanded detail, in priority order: template preview editor, then version, then strategy, otherwise "왼쪽에서 미리보기 템플릿 또는 전략을 선택하세요." Nothing is auto-selected. The preview editor keeps its disabled, locked 전략 버전 만들기 button.
- Exclusive open: opening a template or 새 템플릿 first closes the open strategy / version layers; opening a strategy or version, or starting 새 전략, first closes the open template. Only the open family is closed, so the two families are never open together and Compact (where the master is visible only with nothing open) behaves exactly as before. Built on the existing open / close / back calls; no ViewModel, persistence, or navigation change.
- Unsaved switching (approved HD-SA-01): selecting another master item replaces the open detail, as Back does today; there is no dirty prompt. Template edits are in-memory preview only; real-strategy drafts keep their existing save / discard rules.
- Highlight (approved HD-SA-03): in Expanded only, the open template or the open strategy is marked selected (border, container color, `selected` semantics). Only one family can be marked; Compact and Medium cards are unchanged.
- Scroll: Compact and Medium keep one scroll. Expanded uses a non-scrolling row with one independent scroll per pane; the detail scroll restarts when the open item changes.
- Back, the top-bar back arrow, the drawer prohibition, and the rail Developer Tools rule still follow `layered` (any open layer). Dialogs stay modal and unchanged. The NoticeBanner spans the full width above the panes.
- Physical / rendered validation of the Expanded layout is deferred with the rest of the feature branch (after B2 Smoke).

## N. 운영 · 감사 adaptive layout (ADMIN-ADAPT-02)

The third separately-approved content gate after NAV-1, for the read-only 운영 · 감사 route only:

| Width class | 운영 · 감사 layout |
| --- | --- |
| Compact | Single pane, unchanged: one scroll; the open operation detail replaces the sections |
| Medium | Same as Compact (two panes beside the rail would each be narrower than a phone) |
| Expanded | Two panes: 360dp left pane, 16dp gap, right pane fills the rest |

| Expanded pane | Content |
| --- | --- |
| Left | 읽기 전용 안내 · 운영 상태 · 최근 실행 |
| Right, no detail open | Audit · 오류 · 앱 정보 · 환경 |
| Right, detail open | The existing operation detail (Loading / Failed / Loaded) |

- `AdminLayout` (pure) owns `modeFor(NavWidthClass)`, the section order, the two pane groups, the right-pane choice, and the highlight. Reading the left pane then the right gives the single-pane order; no section is omitted or repeated. The screen reads the width class from `LocalNavChrome`; no breakpoint values live in `feature/admin`.
- The detail is the existing `AdminUiState.detail` (`openOperation` / `closeDetail`): no new selection state, no auto-selection, and no empty-detail placeholder, since the right pane shows the root sections until an operation is opened. Errors and Audit keep their inline expansion and filter chips; they get no detail surface, and their `Operation #id` text stays plain text.
- Highlight (approved HD-AA-02): in Expanded only, the open operation's row in 최근 실행 is marked selected (container color, `selected` semantics). If a refresh drops that operation from the recent 20, the detail stays open and no row is highlighted.
- Top bar and Back (approved HD-AA-01, unchanged): "실행 상세" while a detail is open, otherwise "운영 · 감사"; Back and the back arrow close the detail first, then leave the route. 운영 · 감사 stays a subordinate route that is never drawer-capable.
- Scroll: Compact and Medium keep one scroll. Expanded uses a non-scrolling row with one independent scroll per pane; the right pane's scroll restarts when it switches between the root sections and an operation, or between operations. The read-only notice scrolls with the left pane.
- Data, sanitizing, query limits, and refresh are unchanged. The entry refresh is not keyed to the width class or the layout mode, so recomposing into another layout does not by itself add a refresh. Normal Activity recreation, including recreation caused by a configuration or window change, may run the existing entry refresh again; that lifecycle behavior predates ADMIN-ADAPT-02 and is unchanged by it.
- Physical / rendered validation of the Expanded layout is deferred with the rest of the feature branch (after B2 Smoke).

## O. Paper Trading Adaptive Layout (PAPER-ADAPT-02)

The fourth separately-approved content gate after NAV-1, for the 모의투자 primary tab only:

| Width class | 모의투자 layout |
| --- | --- |
| Compact | Single column, unchanged: notice · 모의투자 목록 · 자동운영 · selected Run detail · 최근 실행 기록 in one scroll |
| Medium | Same as Compact (a list-detail split beside the rail would make each pane narrower than a phone) |
| Expanded | Notice full width on top; below it a 360dp left pane, 16dp gap, right pane fills the rest |

| Expanded area | Content |
| --- | --- |
| Full width | The existing dismissible notice, only while there is one |
| Left | 모의투자 목록 (with 새 모의투자) · 자동운영 (with 지금 실행) · 최근 실행 기록 |
| Right, no Run | Blank (approved HD-PA-01): no placeholder and no new wording; the Run list already shows the empty state |
| Right, Run selected | The existing Run detail (Loading / Failed with 다시 시도 / Loaded) with every existing write control |

- `PaperLayout` (pure) owns `modeFor(NavWidthClass)`, the single-pane order, the full-width / left / right groups, the right-pane choice, and the right-pane key. Notice, then the left pane, then the right pane cover every single-pane section exactly once (approved HD-PA-02). The screen reads the width class from `LocalNavChrome`; no breakpoint values live in `feature/paper`.
- Selection is unchanged: the existing `selectedRunId`, `selectRun`, and `defaultSelection(runs)` drive both layouts; no new or duplicate selection state. The selected Run keeps its existing primary border in the list; no new highlight style.
- 자동운영 and 최근 실행 기록 stay global (not filtered by the selected Run). Dialogs stay modal with a single instance for both layouts.
- Scroll: Compact and Medium keep one scroll. Expanded uses a non-scrolling row with one independent scroll per pane; the right pane's scroll restarts when the selected Run changes.
- Back is unchanged: the detail is inline in both layouts, so no Back handler, back arrow, or route is added. 모의투자 stays a drawer-capable primary tab.
- Orders, executions, positions, cash ledger, snapshots, Run identity, policies, the forward runtime, WorkManager, and the database are unchanged; no real KIS trading.
- No new NAV-CLEANUP item; NAV-CLEANUP-10 stays deferred.
- Physical / rendered validation of the Expanded layout is deferred with the rest of the feature branch (after B2 Smoke).

## P. Performance Adaptive Layout (PERF-ADAPT-02)

The fifth separately-approved content gate after NAV-1, for the 성과 primary tab only:

| Width class | 성과 layout |
| --- | --- |
| Compact | Single column, unchanged: 모의투자 선택 (with 모의투자 비교) · selected Run detail in one scroll |
| Medium | Same as Compact (a list-detail split beside the rail would make each pane narrower than a phone) |
| Expanded | A 360dp left pane, 16dp gap, right pane fills the rest |
| Expanded, comparison open | The existing 모의투자 비교 section full width in one scroll, replacing both panes |

| Expanded area | Content |
| --- | --- |
| Left | 모의투자 선택 with the 모의투자 비교 button in its header (approved HD-PF-03) |
| Right, no detail | Blank (approved HD-PF-01): no placeholder and no new wording; the Run selector already shows the empty, loading, or failed list |
| Right, Run selected | The existing Run detail (Loading / Failed with 다시 시도 / Loaded cards and chart) |
| Comparison | The existing comparison section full width, no width cap and no redesign (approved HD-PF-02) |

- `PerformanceLayout` (pure) owns `modeFor(NavWidthClass)`, the single-pane order, the left / right groups, the right-pane choice, and the right-pane key. The left and right panes cover every single-pane section exactly once. The screen reads the width class from `LocalNavChrome`; no breakpoint values live in `feature/performance`.
- Selection is unchanged: the existing `selectedRunId`, `selectRun`, and `defaultSelection(runs)` drive both layouts; no new or duplicate selection state. The selected Run keeps its existing highlight in the list.
- Card contents, labels, metrics, the equity chart, comparison selection, and loading / error semantics are unchanged; the Compact and Medium branch renders the same sections in the same order.
- Scroll: Compact and Medium keep one scroll. Expanded uses a non-scrolling row with one independent scroll per pane; the right pane's scroll restarts when the selected Run changes.
- Accepted limitation: in Expanded, opening the comparison replaces the panes, so closing it restarts both pane scroll positions at the top. Selection and the loaded detail are kept.
- Back is unchanged from PERF-NAV-02: the open comparison is the only in-screen layer. `LayerBackHandler` and the `TabTopBar` back arrow close it through `closeComparison`, as does the existing in-section close action. While it is open the drawer stays blocked and the rail DevTools entry stays disabled, exactly as before. No route, `NavHost`, or raw Back handler is added.
- `PerformanceViewModel`, `PerformancePresenter`, the data source, analytics, Room, and the forward runtime are unchanged; the layout adds no data reads. No real KIS trading.
- No new NAV-CLEANUP item; NAV-CLEANUP-10 stays deferred.
- Physical / rendered validation of the Expanded layout is deferred with the rest of the feature branch (after B2 Smoke).
