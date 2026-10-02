# 165 UI Navigation / Adaptive Architecture

| Item | Value |
| --- | --- |
| Document status | **NAV-1 IMPLEMENTED ON FEATURE BRANCH** |
| Merge status | **NOT MERGED TO MAIN** (`feature/ui-strategy-admin-demo`) |
| Physical UI | **NOT YET VERIFIED** |
| Scope | Navigation chrome only: drawer, bottom bar, rail, top-level top bar. |
| Related | docs/151 (UI/UX baseline, §3 navigation) |

This document records the NAV-1 adaptive navigation shell and its human-approved decisions. It does not change Room / PostgreSQL schema, runtime, providers, scheduler, strategy engine, or any screen's content.

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
- On any other state (Settings, 운영 · 감사, legacy tools, `database_info`, `dashboard`, Stocks detail / theme browse, Strategy detail / version / template preview), the 개발자도구 item is shown **disabled**. It is disabled rather than hidden: the rail keeps a stable layout and the result is deterministic. All other rail items stay enabled. Settings › 개발자 도구 remains the alternate path.

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
| 3 | Screen in-screen layer open (Stocks detail / theme browse, Strategy strategy / version / template preview, Admin detail) | the screen's layer back handler closes the layer (`LayerBackHandler` in Stocks / Strategy, the existing `BackHandler` in Admin) |
| 4 | Pushed subordinate destination | `popBackStack()` |
| 5 | Top-level destination | existing tab behaviour (to 홈, then leave the app) |

Material3 1.4.0's `ModalNavigationDrawer` registers no back handler itself, and the shell uses the `ModalDrawerSheet` overload without `drawerState`, so the shell's drawer handler is the only drawer handler.

### Back priority invariant

- **Drawer priority is state-driven.** `BackPriority` (in `BJStockDrawer.kt`) expresses the table above as state; a level wins because every lower level is disabled, never because its callback was registered later.
- **The NavHost subtree is disabled while the drawer is open.** The shell wraps its `content()` (the single NavHost and every screen) in a child back dispatcher, `rememberNavigationEventDispatcherOwner(enabled = BackPriority.contentBackEnabled(drawerOpen))`, provided through `LocalNavigationEventDispatcherOwner` (navigationevent-compose 1.0.0, already on the classpath through activity-compose 1.13; no dependency added). This also covers NavHost's own internal back handler, which the app cannot gate directly. The drawer handler lives on the Activity dispatcher with `enabled = BackPriority.drawerHandlerEnabled(drawerOpen)`.
- **Screen-level BackHandlers are disabled while the drawer is open.** Stocks and Strategy use `LayerBackHandler`, enabled by `BackPriority.layerHandlerEnabled(hasInScreenLayer, drawerOpen)`, reading the read-only `drawerOpen` from `LocalNavChrome`. Screens never see the mutable `DrawerState`. Admin's detail handler is unchanged: Admin is never drawer-capable, and the disabled subtree covers it anyway.
- **The drawer closes automatically if `DrawerAccess` becomes false** while it is open (`DrawerAccess.mustClose`): an in-screen layer opens, or the route changes to Settings, 운영 · 감사, or a legacy screen.
- **No lifecycle or callback-registration-order dependency is required.** There is no lifecycle-keyed re-registration; `BackPriorityDispatchTest` verifies on the real dispatcher that the drawer wins whether it registered before or after NavHost and screen handlers. Within the enabled NavHost subtree, a screen layer handler taking precedence over NavHost's pop (rows 3 vs 4–5) is the standard Navigation-Compose contract and is unchanged by NAV-1.

## H. Subordinate-screen drawer prohibition

Drawer prohibition applies to subordinate/detail state at all width classes (HD-NAV-08).

- **Drawer-capable state** is defined in one place, `DrawerAccess.isDrawerCapable(route, hasInScreenLayer)`: the current route is a `PrimaryTab` route **and** that screen has no in-screen layer open.
- The in-screen-layer signal has a single source: the `onBack` that Stocks and Strategy already pass to `TabTopBar` only while a layer is open (the same value that makes the back arrow win over the hamburger). `TabTopBar` reports it to the shell keyed by its back-stack entry, so a screen transition cannot overwrite another entry's state.
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
| NAV-CLEANUP-07 | Home adaptive 2-column layout deferred. | docs only |
| NAV-CLEANUP-08 | Strategy List-Detail deferred. | docs only |
| NAV-CLEANUP-09 | Admin List-Detail deferred. | docs only |
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
