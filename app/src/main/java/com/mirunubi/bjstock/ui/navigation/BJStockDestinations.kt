package com.mirunubi.bjstock.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.mirunubi.bjstock.ui.icons.BJStockIcons

object BJStockRoutes {
    const val HOME = "home"
    const val STOCKS = "stocks"
    const val STRATEGY = "strategy"
    const val PAPER_TRADING = "paper_trading"
    const val PERFORMANCE = "performance"
    const val SETTINGS = "settings"
    const val ADMIN = "admin"

    /** Route strings of screens that existed before UI-1; kept unchanged. */
    const val DEV_DASHBOARD = "dashboard"
    const val DATABASE_INFO = "database_info"
    const val KIS_SETTINGS = "kis_settings"
    const val MARKET_DATA = "market_data_test"
    const val INSTRUMENT_MASTER = "instrument_master"
    const val THEMES = "themes"
    const val FACTOR_TEST = "factor_test"
    const val STRATEGY_LAB = "strategy_lab"
    const val PAPER_LAB = "paper_lab"
    const val FORWARD_TEST = "forward_test"
    const val COMPARE_RUNS = "compare_runs"
    const val AI_ADVISOR = "ai_advisor"

    const val START = HOME

    val LEGACY: List<String> = listOf(
        DEV_DASHBOARD,
        DATABASE_INFO,
        KIS_SETTINGS,
        MARKET_DATA,
        INSTRUMENT_MASTER,
        THEMES,
        FACTOR_TEST,
        STRATEGY_LAB,
        PAPER_LAB,
        FORWARD_TEST,
        COMPARE_RUNS,
        AI_ADVISOR,
    )
}

/**
 * The five top-level destinations (docs/151 §3, docs/165). All five stay in the drawer and rail;
 * the compact bottom bar shows only [BottomNav.tabs]. Settings is never a tab.
 */
enum class PrimaryTab(val route: String, val label: String) {
    HOME(BJStockRoutes.HOME, "홈"),
    STOCKS(BJStockRoutes.STOCKS, "종목"),
    STRATEGY(BJStockRoutes.STRATEGY, "전략"),
    PAPER_TRADING(BJStockRoutes.PAPER_TRADING, "모의투자"),
    PERFORMANCE(BJStockRoutes.PERFORMANCE, "성과"),
    ;

    val icon: ImageVector
        get() = when (this) {
            HOME -> BJStockIcons.Home
            STOCKS -> BJStockIcons.Stocks
            STRATEGY -> BJStockIcons.Strategy
            PAPER_TRADING -> BJStockIcons.PaperTrading
            PERFORMANCE -> BJStockIcons.Performance
        }
}

data class NavEntry(val title: String, val description: String, val route: String)

data class NavSection(val title: String, val entries: List<NavEntry>)

/** Compact bottom navigation (docs/165 HD-NAV-03). 종목 and 성과 stay top-level destinations via drawer and rail. */
object BottomNav {
    val tabs: List<PrimaryTab> = listOf(PrimaryTab.HOME, PrimaryTab.STRATEGY, PrimaryTab.PAPER_TRADING)

    /** Bottom item to highlight on [current]; null on 종목 / 성과, so no bottom item is falsely selected. */
    fun selectedItem(current: PrimaryTab): PrimaryTab? = current.takeIf { it in tabs }
}

/** Where a drawer or rail item leads. Tabs keep the [navigateToTab] back stack; pushes use launchSingleTop. */
sealed interface ShellTarget {
    data class Tab(val tab: PrimaryTab) : ShellTarget

    data class Push(val route: String) : ShellTarget

    /** Opens the Developer Tools group inside the drawer; not a NavHost destination. */
    data object DevTools : ShellTarget
}

data class ShellItem(val label: String, val icon: ImageVector?, val target: ShellTarget)

data class ShellGroup(val title: String, val items: List<ShellItem>)

/** Compact drawer IA (docs/165 HD-NAV-04 / HD-NAV-05). */
object DrawerMenu {
    val primary: List<ShellItem> = PrimaryTab.entries.map { ShellItem(it.label, it.icon, ShellTarget.Tab(it)) }
    val admin = ShellItem("운영 · 감사", BJStockIcons.Admin, ShellTarget.Push(BJStockRoutes.ADMIN))
    val settings = ShellItem("설정", BJStockIcons.Settings, ShellTarget.Push(BJStockRoutes.SETTINGS))
    val devTools = ShellItem("개발자도구", BJStockIcons.DevTools, ShellTarget.DevTools)

    val groups: List<ShellGroup> = listOf(
        ShellGroup("주요", primary),
        ShellGroup("운영", listOf(admin)),
        ShellGroup("앱", listOf(settings, devTools)),
    )

    // NAV-CLEANUP-01, NAV-CLEANUP-02:
    // database_info and the legacy dashboard are intentionally not promoted into the drawer / rail IA.
    // They remain reachable from Settings for compatibility. Re-evaluate after physical UI validation.
    val developerTools: List<ShellItem> = listOf(
        ShellItem("KIS 연결", null, ShellTarget.Push(BJStockRoutes.KIS_SETTINGS)),
        ShellItem("테마 관리", null, ShellTarget.Push(BJStockRoutes.THEMES)),
        ShellItem("종목 마스터", null, ShellTarget.Push(BJStockRoutes.INSTRUMENT_MASTER)),
        ShellItem("Market Data Test", null, ShellTarget.Push(BJStockRoutes.MARKET_DATA)),
        ShellItem("Factor Test", null, ShellTarget.Push(BJStockRoutes.FACTOR_TEST)),
        ShellItem("Strategy Lab", null, ShellTarget.Push(BJStockRoutes.STRATEGY_LAB)),
        ShellItem("Paper Lab", null, ShellTarget.Push(BJStockRoutes.PAPER_LAB)),
        ShellItem("Forward Test", null, ShellTarget.Push(BJStockRoutes.FORWARD_TEST)),
        ShellItem("Run 비교", null, ShellTarget.Push(BJStockRoutes.COMPARE_RUNS)),
        ShellItem("AI Advisor", null, ShellTarget.Push(BJStockRoutes.AI_ADVISOR)),
    )
}

/** Medium / Expanded rail IA (docs/165 HD-NAV-09); sections are separated by dividers, with no overflow item. */
object RailMenu {
    val sections: List<List<ShellItem>> = listOf(
        DrawerMenu.primary,
        listOf(DrawerMenu.admin),
        listOf(DrawerMenu.settings, DrawerMenu.devTools),
    )
}

/** Drawer / rail item that represents [route]; developer-tool routes map to the Developer Tools group. */
object ShellSelection {
    fun targetFor(route: String?): ShellTarget? {
        if (route == null) return null
        PrimaryTab.entries.firstOrNull { it.route == route }?.let { return ShellTarget.Tab(it) }
        return when {
            route == BJStockRoutes.ADMIN || route == BJStockRoutes.SETTINGS -> ShellTarget.Push(route)
            DrawerMenu.developerTools.any { it.target == ShellTarget.Push(route) } -> ShellTarget.DevTools
            else -> null
        }
    }
}

// NAV-CLEANUP-04:
// TabHubs and TabHubScreen are unreachable while every tab is dedicated; retained for compatibility.
// Remove only in a separately approved navigation cleanup gate.
/** Temporary tab content for UI-1: links to the existing screens until each tab is redesigned. */
object TabHubs {
    /** Tabs with their own product screen; any other tab would use the temporary link hub. */
    val dedicated: Set<PrimaryTab> = PrimaryTab.entries.toSet()

    fun forTab(tab: PrimaryTab): List<NavEntry> = when (tab) {
        PrimaryTab.HOME, PrimaryTab.STOCKS, PrimaryTab.STRATEGY, PrimaryTab.PAPER_TRADING, PrimaryTab.PERFORMANCE -> emptyList()
    }
}

/** Routes the Stocks screen links to; theme CRUD stays on the existing Themes screen. */
object StocksLinks {
    const val THEME_MANAGEMENT = BJStockRoutes.THEMES
}

// NAV-CLEANUP-03:
// Settings retains developer-tool links as alternate entry points next to the drawer / rail Developer Tools group.
// Remove duplicates only in a separately approved navigation cleanup gate.
object SettingsMenu {
    val sections = listOf(
        NavSection(
            "연결 및 데이터",
            listOf(
                NavEntry("KIS 연결", "KIS API 인증 설정", BJStockRoutes.KIS_SETTINGS),
                NavEntry("DB 정보 · API 오류", "로컬 DB 상태와 최근 7일 API 오류", BJStockRoutes.DATABASE_INFO),
            ),
        ),
        NavSection(
            "운영",
            listOf(
                NavEntry("운영 · 감사", "운영 상태 · 최근 실행 · Audit · 오류 (읽기 전용)", BJStockRoutes.ADMIN),
            ),
        ),
        NavSection(
            "개발자 도구",
            listOf(
                NavEntry("기존 개발 대시보드", "UI-1 이전 시작 화면", BJStockRoutes.DEV_DASHBOARD),
                NavEntry("포워드 테스트", "Run·자동운영·재시도", BJStockRoutes.FORWARD_TEST),
                NavEntry("Run 비교", "전략 Run 성과 비교", BJStockRoutes.COMPARE_RUNS),
                NavEntry("전략 실험실", "전략·버전 편집", BJStockRoutes.STRATEGY_LAB),
                NavEntry("모의매매 실험실", "주문·체결 수동 점검", BJStockRoutes.PAPER_LAB),
                NavEntry("팩터 테스트", "팩터 계산 점검", BJStockRoutes.FACTOR_TEST),
                NavEntry("종목 마스터", "종목 목록 동기화", BJStockRoutes.INSTRUMENT_MASTER),
                NavEntry("시세 조회 테스트", "KIS 시세 조회 점검", BJStockRoutes.MARKET_DATA),
                NavEntry("테마", "테마 관리", BJStockRoutes.THEMES),
                NavEntry("AI 어드바이저", "현재 꺼짐", BJStockRoutes.AI_ADVISOR),
            ),
        ),
    )
}
