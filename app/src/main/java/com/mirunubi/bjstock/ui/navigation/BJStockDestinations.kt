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

/** The five bottom-navigation destinations (docs/151 §3). Settings is a top-right action, not a tab. */
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

/** Temporary tab content for UI-1: links to the existing screens until each tab is redesigned. */
object TabHubs {
    val stocks = listOf(
        NavEntry("시세 조회", "KIS 현재가·일봉 조회", BJStockRoutes.MARKET_DATA),
        NavEntry("테마", "테마와 관심종목 관리", BJStockRoutes.THEMES),
        NavEntry("종목 마스터", "KOSPI·KOSDAQ 종목 목록 동기화", BJStockRoutes.INSTRUMENT_MASTER),
        NavEntry("팩터 점수", "종목별 팩터 계산 결과", BJStockRoutes.FACTOR_TEST),
    )

    val strategy = listOf(
        NavEntry("전략 관리", "전략·버전·팩터 가중치·신호 규칙", BJStockRoutes.STRATEGY_LAB),
    )

    val paperTrading = listOf(
        NavEntry("포워드 테스트", "모의투자 Run, 자동운영, 지금 실행", BJStockRoutes.FORWARD_TEST),
        NavEntry("모의매매 실험실", "주문·체결 규칙 수동 점검", BJStockRoutes.PAPER_LAB),
    )

    val performance = listOf(
        NavEntry("Run 성과", "누적수익률·MDD·자산 추이 (포워드 테스트 화면)", BJStockRoutes.FORWARD_TEST),
        NavEntry("Run 비교", "전략 Run 성과 비교", BJStockRoutes.COMPARE_RUNS),
    )

    fun forTab(tab: PrimaryTab): List<NavEntry> = when (tab) {
        PrimaryTab.HOME -> emptyList()
        PrimaryTab.STOCKS -> stocks
        PrimaryTab.STRATEGY -> strategy
        PrimaryTab.PAPER_TRADING -> paperTrading
        PrimaryTab.PERFORMANCE -> performance
    }
}

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
