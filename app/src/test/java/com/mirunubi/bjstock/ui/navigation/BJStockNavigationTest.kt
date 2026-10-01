package com.mirunubi.bjstock.ui.navigation

import com.mirunubi.bjstock.ui.icons.BJStockIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BJStockNavigationTest {
    @Test
    fun appStartsOnHome() {
        assertEquals(BJStockRoutes.HOME, BJStockRoutes.START)
        assertEquals(PrimaryTab.HOME, PrimaryTab.entries.first())
    }

    @Test
    fun fivePrimaryTabs_inOrder_withKoreanLabelsAndIcons() {
        assertEquals(listOf("홈", "종목", "전략", "모의투자", "성과"), PrimaryTab.entries.map { it.label })
        assertEquals(5, PrimaryTab.entries.map { it.route }.toSet().size)
        PrimaryTab.entries.forEach { assertTrue(it.icon.root.size > 0) }
        listOf(BJStockIcons.Settings, BJStockIcons.Back, BJStockIcons.ChevronRight, BJStockIcons.Warning, BJStockIcons.CheckCircle)
            .forEach { assertTrue(it.root.size > 0) }
    }

    @Test
    fun settingsIsNotABottomTab() {
        assertFalse(PrimaryTab.entries.any { it.route == BJStockRoutes.SETTINGS })
        assertFalse(PrimaryTab.entries.any { it.label == "설정" })
    }

    @Test
    fun everyExistingScreenStaysReachable_fromSettingsOrTabs() {
        val settingsRoutes = SettingsMenu.sections.flatMap { it.entries }.map { it.route }.toSet()
        val tabRoutes = PrimaryTab.entries.flatMap { TabHubs.forTab(it) }.map { it.route }.toSet()
        BJStockRoutes.LEGACY.forEach { route ->
            assertTrue("$route must be reachable", route in settingsRoutes || route in tabRoutes)
        }
        BJStockRoutes.LEGACY.forEach { route ->
            assertTrue("$route must be listed under developer tools or Settings", route in settingsRoutes)
        }
    }

    @Test
    fun legacyRouteStrings_areUnchanged() {
        assertEquals(
            listOf(
                "dashboard", "database_info", "kis_settings", "market_data_test", "instrument_master", "themes",
                "factor_test", "strategy_lab", "paper_lab", "forward_test", "compare_runs", "ai_advisor",
            ),
            BJStockRoutes.LEGACY,
        )
        val newRoutes = PrimaryTab.entries.map { it.route } + BJStockRoutes.SETTINGS
        assertTrue(newRoutes.none { it in BJStockRoutes.LEGACY })
    }

    @Test
    fun aiAdvisor_isOnlyASecondaryRoute() {
        val tabRoutes = PrimaryTab.entries.flatMap { TabHubs.forTab(it) }.map { it.route } + PrimaryTab.entries.map { it.route }
        assertFalse(BJStockRoutes.AI_ADVISOR in tabRoutes)
        val developer = SettingsMenu.sections.single { it.title == "개발자 도구" }.entries.map { it.route }
        assertTrue(BJStockRoutes.AI_ADVISOR in developer)
    }

    @Test
    fun stocksTab_opensTheStocksScreen_notADeveloperLinkHub() {
        assertEquals(setOf(PrimaryTab.HOME, PrimaryTab.STOCKS), TabHubs.dedicated)
        assertTrue(TabHubs.forTab(PrimaryTab.STOCKS).isEmpty())
        val hubRoutes = PrimaryTab.entries.flatMap { TabHubs.forTab(it) }.map { it.route }
        listOf(BJStockRoutes.MARKET_DATA, BJStockRoutes.INSTRUMENT_MASTER, BJStockRoutes.FACTOR_TEST, BJStockRoutes.THEMES)
            .forEach { assertFalse(it in hubRoutes) }
    }

    @Test
    fun stocksDeveloperScreens_stayUnderDeveloperTools_andThemeManagementLinksToThemes() {
        val developer = SettingsMenu.sections.single { it.title == "개발자 도구" }.entries.map { it.route }
        listOf(BJStockRoutes.MARKET_DATA, BJStockRoutes.INSTRUMENT_MASTER, BJStockRoutes.THEMES, BJStockRoutes.FACTOR_TEST)
            .forEach { assertTrue(it, it in developer) }
        assertEquals(BJStockRoutes.THEMES, StocksLinks.THEME_MANAGEMENT)
    }

    @Test
    fun tabShells_linkToTheirExistingScreens() {
        assertTrue(TabHubs.forTab(PrimaryTab.HOME).isEmpty())
        assertEquals(listOf(BJStockRoutes.STRATEGY_LAB), TabHubs.strategy.map { it.route })
        assertTrue(BJStockRoutes.FORWARD_TEST in TabHubs.paperTrading.map { it.route })
        assertTrue(BJStockRoutes.COMPARE_RUNS in TabHubs.performance.map { it.route })
        val settings = SettingsMenu.sections.first().entries.map { it.route }
        assertEquals(listOf(BJStockRoutes.KIS_SETTINGS, BJStockRoutes.DATABASE_INFO), settings)
    }

    @Test
    fun visibleNavigationText_isKorean() {
        val titles = PrimaryTab.entries.map { it.label } +
            SettingsMenu.sections.map { it.title } +
            (SettingsMenu.sections.flatMap { it.entries } + PrimaryTab.entries.flatMap { TabHubs.forTab(it) }).map { it.title }
        titles.forEach { title -> assertTrue(title, title.any { it in '\uAC00'..'\uD7A3' }) }
    }
}
