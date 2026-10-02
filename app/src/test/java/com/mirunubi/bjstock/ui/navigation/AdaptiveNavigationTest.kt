package com.mirunubi.bjstock.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationTest {
    private fun routesOf(items: List<ShellItem>): List<String> = items.map { (it.target as ShellTarget.Push).route }

    @Test
    fun primaryTab_keepsAllFiveDestinations() {
        assertEquals(
            listOf(PrimaryTab.HOME, PrimaryTab.STOCKS, PrimaryTab.STRATEGY, PrimaryTab.PAPER_TRADING, PrimaryTab.PERFORMANCE),
            PrimaryTab.entries,
        )
    }

    @Test
    fun compactBottomBar_isExactlyHomeStrategyPaperTrading() {
        assertEquals(listOf(PrimaryTab.HOME, PrimaryTab.STRATEGY, PrimaryTab.PAPER_TRADING), BottomNav.tabs)
        assertEquals(listOf("홈", "전략", "모의투자"), BottomNav.tabs.map { it.label })
    }

    @Test
    fun drawerRoot_isExactlyTheApprovedIa() {
        assertEquals(listOf("주요", "운영", "앱"), DrawerMenu.groups.map { it.title })
        assertEquals(
            listOf(
                PrimaryTab.HOME, PrimaryTab.STOCKS, PrimaryTab.STRATEGY, PrimaryTab.PAPER_TRADING, PrimaryTab.PERFORMANCE,
            ).map { ShellTarget.Tab(it) },
            DrawerMenu.groups[0].items.map { it.target },
        )
        assertEquals(listOf("홈", "종목", "전략", "모의투자", "성과"), DrawerMenu.groups[0].items.map { it.label })
        assertEquals(listOf(ShellTarget.Push(BJStockRoutes.ADMIN)), DrawerMenu.groups[1].items.map { it.target })
        assertEquals(listOf("운영 · 감사"), DrawerMenu.groups[1].items.map { it.label })
        assertEquals(
            listOf(ShellTarget.Push(BJStockRoutes.SETTINGS), ShellTarget.DevTools),
            DrawerMenu.groups[2].items.map { it.target },
        )
        assertEquals(listOf("설정", "개발자도구"), DrawerMenu.groups[2].items.map { it.label })
    }

    @Test
    fun developerTools_mapToTheExistingRoutes_inApprovedOrder() {
        assertEquals(
            listOf(
                "KIS 연결", "테마 관리", "종목 마스터", "Market Data Test", "Factor Test",
                "Strategy Lab", "Paper Lab", "Forward Test", "Run 비교", "AI Advisor",
            ),
            DrawerMenu.developerTools.map { it.label },
        )
        assertEquals(
            listOf(
                BJStockRoutes.KIS_SETTINGS, BJStockRoutes.THEMES, BJStockRoutes.INSTRUMENT_MASTER, BJStockRoutes.MARKET_DATA,
                BJStockRoutes.FACTOR_TEST, BJStockRoutes.STRATEGY_LAB, BJStockRoutes.PAPER_LAB, BJStockRoutes.FORWARD_TEST,
                BJStockRoutes.COMPARE_RUNS, BJStockRoutes.AI_ADVISOR,
            ),
            routesOf(DrawerMenu.developerTools),
        )
        assertTrue(routesOf(DrawerMenu.developerTools).all { it in BJStockRoutes.LEGACY })
    }

    @Test
    fun databaseInfoAndLegacyDashboard_areNotPromoted_butStayReachableFromSettings() {
        val drawerTargets = DrawerMenu.groups.flatMap { it.items }.map { it.target } + DrawerMenu.developerTools.map { it.target }
        val railTargets = RailMenu.sections.flatten().map { it.target }
        listOf(BJStockRoutes.DATABASE_INFO, BJStockRoutes.DEV_DASHBOARD).forEach { route ->
            assertFalse(route, ShellTarget.Push(route) in drawerTargets)
            assertFalse(route, ShellTarget.Push(route) in railTargets)
            assertNull(route, ShellSelection.targetFor(route))
        }
        val settingsRoutes = SettingsMenu.sections.flatMap { it.entries }.map { it.route }
        assertTrue(BJStockRoutes.DATABASE_INFO in settingsRoutes)
        assertTrue(BJStockRoutes.DEV_DASHBOARD in settingsRoutes)
    }

    @Test
    fun kisLabel_staysKisConnection() {
        val kis = DrawerMenu.developerTools.single { it.target == ShellTarget.Push(BJStockRoutes.KIS_SETTINGS) }
        assertEquals("KIS 연결", kis.label)
        assertFalse(DrawerMenu.developerTools.any { it.label == "KIS 설정" })
    }

    @Test
    fun settingsKeepsDeveloperLinks_asAlternateEntryPoints() {
        val settingsRoutes = SettingsMenu.sections.flatMap { it.entries }.map { it.route }.toSet()
        routesOf(DrawerMenu.developerTools).forEach { assertTrue(it, it in settingsRoutes) }
    }

    @Test
    fun rail_exposesFivePrimaries_thenAdmin_thenSettingsAndDevTools_withoutOverflow() {
        assertEquals(3, RailMenu.sections.size)
        assertEquals(PrimaryTab.entries.map { ShellTarget.Tab(it) }, RailMenu.sections[0].map { it.target })
        assertEquals(listOf(ShellTarget.Push(BJStockRoutes.ADMIN)), RailMenu.sections[1].map { it.target })
        assertEquals(listOf(ShellTarget.Push(BJStockRoutes.SETTINGS), ShellTarget.DevTools), RailMenu.sections[2].map { it.target })
        assertEquals(8, RailMenu.sections.flatten().size)
        RailMenu.sections.flatten().forEach { assertTrue(it.label, it.icon != null) }
    }

    @Test
    fun stocksAndPerformance_doNotFalselySelectABottomItem() {
        assertNull(BottomNav.selectedItem(PrimaryTab.STOCKS))
        assertNull(BottomNav.selectedItem(PrimaryTab.PERFORMANCE))
        assertEquals(PrimaryTab.HOME, BottomNav.selectedItem(PrimaryTab.HOME))
        assertEquals(PrimaryTab.STRATEGY, BottomNav.selectedItem(PrimaryTab.STRATEGY))
        assertEquals(PrimaryTab.PAPER_TRADING, BottomNav.selectedItem(PrimaryTab.PAPER_TRADING))
    }

    @Test
    fun stocksAndPerformance_stayRoutableTopLevelDestinations() {
        assertEquals(ShellTarget.Tab(PrimaryTab.STOCKS), ShellSelection.targetFor(BJStockRoutes.STOCKS))
        assertEquals(ShellTarget.Tab(PrimaryTab.PERFORMANCE), ShellSelection.targetFor(BJStockRoutes.PERFORMANCE))
        assertTrue(DrawerMenu.primary.any { it.target == ShellTarget.Tab(PrimaryTab.STOCKS) })
        assertTrue(DrawerMenu.primary.any { it.target == ShellTarget.Tab(PrimaryTab.PERFORMANCE) })
    }

    @Test
    fun shellSelection_mapsDeveloperRoutesToTheGroup_andIgnoresUnknownRoutes() {
        routesOf(DrawerMenu.developerTools).forEach { assertEquals(it, ShellTarget.DevTools, ShellSelection.targetFor(it)) }
        assertEquals(ShellTarget.Push(BJStockRoutes.ADMIN), ShellSelection.targetFor(BJStockRoutes.ADMIN))
        assertEquals(ShellTarget.Push(BJStockRoutes.SETTINGS), ShellSelection.targetFor(BJStockRoutes.SETTINGS))
        assertNull(ShellSelection.targetFor(null))
        assertNull(ShellSelection.targetFor("unknown"))
    }

    @Test
    fun widthClass_breakpoints() {
        assertEquals(NavWidthClass.COMPACT, NavWidthClass.fromWidthDp(0))
        assertEquals(NavWidthClass.COMPACT, NavWidthClass.fromWidthDp(411))
        assertEquals(NavWidthClass.COMPACT, NavWidthClass.fromWidthDp(599))
        assertEquals(NavWidthClass.MEDIUM, NavWidthClass.fromWidthDp(600))
        assertEquals(NavWidthClass.MEDIUM, NavWidthClass.fromWidthDp(839))
        assertEquals(NavWidthClass.EXPANDED, NavWidthClass.fromWidthDp(840))
        assertEquals(NavWidthClass.EXPANDED, NavWidthClass.fromWidthDp(1280))
    }

    @Test
    fun onlyCompactUsesDrawerAndBottomBar_mediumAndExpandedUseRail() {
        assertFalse(NavWidthClass.COMPACT.usesRail)
        assertTrue(NavWidthClass.MEDIUM.usesRail)
        assertTrue(NavWidthClass.EXPANDED.usesRail)
    }

    @Test
    fun back_inDeveloperToolsDepth_returnsToDrawerRoot() {
        assertEquals(DrawerBack.SHOW_ROOT, DrawerNavigation.back(DrawerPage.DEV_TOOLS))
    }

    @Test
    fun back_onDrawerRoot_closesDrawer() {
        assertEquals(DrawerBack.CLOSE, DrawerNavigation.back(DrawerPage.ROOT))
    }

    @Test
    fun drawerOpen_disablesTheStrategyLayerHandler() {
        val strategyLayered = true
        assertFalse(BackPriority.layerHandlerEnabled(strategyLayered, drawerOpen = true))
        assertTrue(BackPriority.layerHandlerEnabled(strategyLayered, drawerOpen = false))
    }

    @Test
    fun drawerOpen_disablesTheStocksLayerHandler() {
        val stocksLayered = true
        assertFalse(BackPriority.layerHandlerEnabled(stocksLayered, drawerOpen = true))
        assertTrue(BackPriority.layerHandlerEnabled(stocksLayered, drawerOpen = false))
        assertFalse(BackPriority.layerHandlerEnabled(hasInScreenLayer = false, drawerOpen = false))
    }

    @Test
    fun drawerOpen_disablesTheWholeNavHostSubtree_andEnablesOnlyTheDrawerHandler() {
        assertFalse(BackPriority.contentBackEnabled(drawerOpen = true))
        assertTrue(BackPriority.drawerHandlerEnabled(drawerOpen = true))
        assertTrue(BackPriority.contentBackEnabled(drawerOpen = false))
        assertFalse(BackPriority.drawerHandlerEnabled(drawerOpen = false))
    }

    @Test
    fun backPriority_resolvesByState_inTheApprovedOrder() {
        DrawerPage.entries.forEach { page ->
            listOf(true, false).forEach { layered ->
                val open = BackPriority.resolve(drawerOpen = true, page = page, hasInScreenLayer = layered)
                assertEquals(if (page == DrawerPage.DEV_TOOLS) BackLevel.DRAWER_DEV_TOOLS else BackLevel.DRAWER_ROOT, open)
            }
        }
        assertEquals(BackLevel.SCREEN_LAYER, BackPriority.resolve(drawerOpen = false, page = DrawerPage.ROOT, hasInScreenLayer = true))
        assertEquals(BackLevel.NAV_HOST, BackPriority.resolve(drawerOpen = false, page = DrawerPage.ROOT, hasInScreenLayer = false))
    }

    @Test
    fun enabledHandlers_matchTheResolvedLevel_forEveryState() {
        listOf(true, false).forEach { drawerOpen ->
            listOf(true, false).forEach { layered ->
                val level = BackPriority.resolve(drawerOpen, DrawerPage.ROOT, layered)
                val drawer = BackPriority.drawerHandlerEnabled(drawerOpen)
                val content = BackPriority.contentBackEnabled(drawerOpen)
                val layer = content && BackPriority.layerHandlerEnabled(layered, drawerOpen)
                assertEquals("$drawerOpen/$layered", level == BackLevel.DRAWER_ROOT, drawer)
                assertEquals("$drawerOpen/$layered", level == BackLevel.SCREEN_LAYER, layer)
                assertFalse("$drawerOpen/$layered", drawer && content)
            }
        }
    }

    @Test
    fun drawerCapabilityLoss_whileOpen_requestsClose() {
        assertTrue(DrawerAccess.mustClose(drawerOpen = true, drawerCapable = false))
        assertFalse(DrawerAccess.mustClose(drawerOpen = true, drawerCapable = true))
        assertFalse(DrawerAccess.mustClose(drawerOpen = false, drawerCapable = false))
        val strategyRoot = DrawerAccess.isDrawerCapable(BJStockRoutes.STRATEGY, hasInScreenLayer = false)
        val strategyLayer = DrawerAccess.isDrawerCapable(BJStockRoutes.STRATEGY, hasInScreenLayer = true)
        assertTrue(strategyRoot)
        assertTrue(DrawerAccess.mustClose(drawerOpen = true, drawerCapable = strategyLayer))
        (listOf(BJStockRoutes.SETTINGS, BJStockRoutes.ADMIN) + BJStockRoutes.LEGACY).forEach { route ->
            assertTrue(route, DrawerAccess.mustClose(true, DrawerAccess.isDrawerCapable(route, hasInScreenLayer = false)))
        }
    }

    @Test
    fun closingDrawer_resetsDepthToRoot() {
        assertEquals(DrawerPage.ROOT, DrawerNavigation.pageAfterClose)
    }

    @Test
    fun drawerGestures_canOnlyCloseAnOpenDrawer() {
        assertFalse(DrawerNavigation.gesturesEnabled(drawerOpen = false))
        assertTrue(DrawerNavigation.gesturesEnabled(drawerOpen = true))
    }

    @Test
    fun subordinateScreens_keepEveryRailItemExceptTheDrawerBackedDevTools() {
        val subordinate = listOf(BJStockRoutes.SETTINGS, BJStockRoutes.ADMIN) + BJStockRoutes.LEGACY
        subordinate.forEach { route ->
            val capable = DrawerAccess.isDrawerCapable(route, hasInScreenLayer = false)
            RailMenu.sections.flatten().filter { it.target != ShellTarget.DevTools }.forEach { item ->
                assertTrue("$route ${item.label}", DrawerAccess.railItemEnabled(item.target, capable))
            }
        }
    }

    @Test
    fun railDevTools_opensFromEveryTopLevelRoot() {
        PrimaryTab.entries.forEach { tab ->
            val capable = DrawerAccess.isDrawerCapable(tab.route, hasInScreenLayer = false)
            assertTrue(tab.name, capable)
            assertTrue(tab.name, DrawerAccess.railItemEnabled(ShellTarget.DevTools, capable))
        }
    }

    @Test
    fun railDevTools_cannotOpenTheDrawer_fromSettingsAdminOrLegacyTools() {
        (listOf(BJStockRoutes.SETTINGS, BJStockRoutes.ADMIN) + BJStockRoutes.LEGACY).forEach { route ->
            val capable = DrawerAccess.isDrawerCapable(route, hasInScreenLayer = false)
            assertFalse(route, capable)
            assertFalse(route, DrawerAccess.railItemEnabled(ShellTarget.DevTools, capable))
        }
        assertFalse(DrawerAccess.isDrawerCapable(null, hasInScreenLayer = false))
    }

    @Test
    fun stocksAndStrategyInScreenLayers_areNotDrawerCapable() {
        listOf(PrimaryTab.STOCKS, PrimaryTab.STRATEGY).forEach { tab ->
            val capable = DrawerAccess.isDrawerCapable(tab.route, hasInScreenLayer = true)
            assertFalse(tab.name, capable)
            assertFalse(tab.name, DrawerAccess.railItemEnabled(ShellTarget.DevTools, capable))
        }
    }

    @Test
    fun topBar_backArrowWinsOverHamburger_andNeverShowsBoth() {
        assertEquals(TopBarNavIcon.BACK, TopBarNavIcon.resolve(hasBack = true, drawerAvailable = true))
        assertEquals(TopBarNavIcon.BACK, TopBarNavIcon.resolve(hasBack = true, drawerAvailable = false))
        assertEquals(TopBarNavIcon.MENU, TopBarNavIcon.resolve(hasBack = false, drawerAvailable = true))
        assertEquals(TopBarNavIcon.NONE, TopBarNavIcon.resolve(hasBack = false, drawerAvailable = false))
    }
}
