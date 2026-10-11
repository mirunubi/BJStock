package com.mirunubi.bjstock.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level invariants of the NAV-1 shell that have no Compose UI test (docs/165 §E, §G, §H, §J). */
class NavigationShellInvariantTest {
    private fun resolve(relative: String): File =
        listOf(File(relative), File("../$relative"), File("app/$relative")).firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}")

    private val mainRoot: File by lazy { resolve("src/main/java/com/mirunubi/bjstock") }
    private val mainSources: List<File> by lazy { mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    private val navDir: File get() = File(mainRoot, "ui/navigation")
    private val doc: String by lazy {
        listOf(File("../docs/165_UI_NAVIGATION_ADAPTIVE_ARCHITECTURE.md"), File("docs/165_UI_NAVIGATION_ADAPTIVE_ARCHITECTURE.md"))
            .first { it.isFile }
            .readText()
    }

    private fun callsOf(regex: Regex): List<Pair<String, Int>> =
        mainSources.flatMap { file -> regex.findAll(file.readText()).map { file.name to it.range.first }.toList() }

    @Test
    fun exactlyOneNavHostCallSite_inMainActivity() {
        val calls = callsOf(Regex("""(?<![A-Za-z0-9_])NavHost\("""))
        assertEquals(calls.toString(), 1, calls.size)
        assertEquals("MainActivity.kt", calls.single().first)
        assertEquals(1, callsOf(Regex("""rememberNavController\(""")).size)
    }

    @Test
    fun everyRouteIsRegisteredOnce_noSecondGraph() {
        val activity = File(mainRoot, "MainActivity.kt").readText()
        val routeNames = listOf(
            "HOME", "STOCKS", "STRATEGY", "PAPER_TRADING", "PERFORMANCE", "SETTINGS", "ADMIN", "DEV_DASHBOARD", "DATABASE_INFO",
            "THEMES", "KIS_SETTINGS", "MARKET_DATA", "INSTRUMENT_MASTER", "FACTOR_TEST", "STRATEGY_LAB", "PAPER_LAB",
            "FORWARD_TEST", "COMPARE_RUNS", "AI_ADVISOR",
        )
        routeNames.forEach { name ->
            assertEquals(name, 1, Regex("""composable\(BJStockRoutes\.$name\)""").findAll(activity).count())
        }
    }

    @Test
    fun shellInvokesItsNavHostContent_fromOnePosition() {
        val shell = File(navDir, "BJStockShell.kt").readText()
        assertEquals(1, Regex("""(?<![A-Za-z0-9_.])content\(\)""").findAll(shell).count())
        assertFalse(shell.contains("NavHost("))
    }

    @Test
    fun backPriority_isWiredByState() {
        val shell = File(navDir, "BJStockShell.kt").readText()
        assertTrue(shell.contains("BackHandler(enabled = BackPriority.drawerHandlerEnabled(drawerOpen))"))
        assertTrue(shell.contains("rememberNavigationEventDispatcherOwner(enabled = BackPriority.contentBackEnabled(drawerOpen))"))
        assertTrue(shell.contains("LocalNavigationEventDispatcherOwner provides contentBackOwner"))
        assertTrue(shell.contains("if (DrawerAccess.mustClose(drawerOpen, drawerCapable)) drawerState.close()"))
        assertTrue(shell.contains("drawerOpen = drawerOpen,"))
        assertEquals(1, Regex("""BackHandler\(""").findAll(shell).count())
        mainSources.forEach { assertFalse(it.name, it.readText().contains("ModalDrawerSheet(drawerState")) }
    }

    @Test
    fun noLifecycleOrReRegistrationKey_decidesBackPriority() {
        val shell = File(navDir, "BJStockShell.kt").readText()
        listOf("lifecycleState", "currentStateAsState", "LocalLifecycleOwner", "key(", "if (drawerOpen) {").forEach {
            assertFalse(it, shell.contains(it))
        }
    }

    @Test
    fun stocksAndStrategyLayerHandlers_useTheDrawerAwareLayerBackHandler() {
        listOf("feature/stocks/StocksScreen.kt", "feature/strategy/StrategyScreen.kt").forEach { path ->
            val source = File(mainRoot, path).readText()
            assertTrue(path, source.contains("LayerBackHandler(hasInScreenLayer = layered)"))
            assertFalse(path, Regex("""(?<![A-Za-z])BackHandler\(""").containsMatchIn(source))
        }
        val chrome = File(navDir, "BJStockChrome.kt").readText()
        assertTrue(chrome.contains("BackHandler(enabled = BackPriority.layerHandlerEnabled(hasInScreenLayer, drawerOpen)"))
    }

    @Test
    fun railVisibility_dependsOnWidthOnly_soSubordinateScreensKeepTheRail() {
        val shell = File(navDir, "BJStockShell.kt").readText()
        assertTrue(Regex("""if \(widthClass\.usesRail\) \{\s*BJStockRail\(""").containsMatchIn(shell))
        assertEquals(1, Regex("""BJStockRail\(currentRoute = """).findAll(shell).count())
    }

    @Test
    fun drawerOpens_onlyThroughOneGuardedOpener() {
        val shell = File(navDir, "BJStockShell.kt").readText()
        assertEquals(1, Regex("""drawerState\.open\(\)""").findAll(shell).count())
        assertTrue(Regex("""if \(drawerCapableNow\) \{\s*page = start\s*scope\.launch \{ drawerState\.open\(\) \}""").containsMatchIn(shell))
        assertTrue(shell.contains("DrawerAccess.isDrawerCapable("))
        assertTrue(shell.contains("enabled = DrawerAccess.railItemEnabled(item.target, drawerCapable)"))
        mainSources.filter { it.name != "BJStockShell.kt" }.forEach { assertFalse(it.name, it.readText().contains("drawerState.open(")) }
    }

    @Test
    fun inScreenLayerSignal_comesFromTheTopBarBackArrow_ofStocksAndStrategy() {
        val chrome = File(navDir, "BJStockChrome.kt").readText()
        assertTrue(chrome.contains("val hasInScreenLayer = onBack != null"))
        assertTrue(chrome.contains("chrome.reportInScreenLayer(owner, hasInScreenLayer)"))
        assertTrue(chrome.contains("TopBarNavIcon.resolve(hasBack = hasInScreenLayer"))
        val stocks = File(mainRoot, "feature/stocks/StocksScreen.kt").readText()
        val strategy = File(mainRoot, "feature/strategy/StrategyScreen.kt").readText()
        assertTrue(stocks.contains("onBack = if (layered) ({ viewModel.back() }) else null"))
        assertTrue(strategy.contains("onBack = if (layered) back else null"))
        assertTrue(strategy.contains("val layered = previewOpen || state.strategy != null || state.version != null"))
    }

    @Test
    fun performanceComparison_isAnInScreenLayer_closedOnlyByTheExistingCloseAction() {
        val performance = File(mainRoot, "feature/performance/PerformanceScreen.kt").readText()
        assertTrue(performance.contains("val layered = state.comparison.open"))
        assertTrue(performance.contains("LayerBackHandler(hasInScreenLayer = layered) { viewModel.closeComparison() }"))
        assertTrue(performance.contains("onBack = if (layered) viewModel::closeComparison else null"))
        assertTrue(performance.contains("TextButton(onClick = viewModel::closeComparison"))
        assertEquals(1, Regex("""title = PrimaryTab\.PERFORMANCE\.label""").findAll(performance).count())
        listOf("navigate(", "popBackStack(", "navigateToTab(", "BJStockRoutes").forEach {
            assertFalse(it, performance.contains(it))
        }
        File(mainRoot, "feature/performance").listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            assertFalse(file.name, Regex("""(?<![A-Za-z])BackHandler\(""").containsMatchIn(file.readText()))
        }
        val activity = File(mainRoot, "MainActivity.kt").readText()
        assertEquals(20, Regex("""composable\(""").findAll(activity).count())
    }

    @Test
    fun settingsGear_isRemovedFromTheTopLevelTopBar() {
        val chrome = File(navDir, "BJStockChrome.kt").readText()
        val tabTopBar = chrome.substringAfter("fun TabTopBar(").substringBefore("fun BackTopBar(")
        assertFalse(tabTopBar.contains("BJStockIcons.Settings"))
        mainSources.forEach { assertFalse(it.name, it.readText().contains("onOpenSettings")) }
    }

    @Test
    fun hamburgerTopBar_isUsedOnlyByTopLevelScreens() {
        val users = mainSources.filter { it.parentFile != navDir && it.readText().contains("TabTopBar(") }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "HomeScreen.kt", "StocksScreen.kt", "StrategyScreen.kt", "PaperTradingScreen.kt", "PerformanceScreen.kt",
                "TabHubScreen.kt",
            ),
            users,
        )
    }

    @Test
    fun architectureDocument_recordsStatusDecisionsAndAllCleanupIds() {
        listOf("NAV-1 IMPLEMENTED ON FEATURE BRANCH", "NOT MERGED TO MAIN", "NOT YET VERIFIED").forEach {
            assertTrue(it, doc.contains(it))
        }
        (1..14).forEach { assertTrue("HD-NAV-%02d".format(it), doc.contains("HD-NAV-%02d".format(it))) }
        assertTrue(doc.contains("Drawer prohibition applies to subordinate/detail state at all width classes."))
        assertTrue(
            doc.contains(
                "NavigationRail may remain visible on subordinate screens, but any Rail action that would require the " +
                    "ModalNavigationDrawer is unavailable while the current screen/layer is subordinate.",
            ),
        )
        (1..10).forEach { assertTrue("NAV-CLEANUP-%02d".format(it), doc.contains("NAV-CLEANUP-%02d".format(it))) }
        assertTrue(doc.contains("""rg "NAV-CLEANUP" app docs"""))
    }

    @Test
    fun everyCleanupMarkerInCode_isDocumented() {
        val ids = mainSources.flatMap { Regex("""NAV-CLEANUP-\d{2}""").findAll(it.readText()).map { m -> m.value }.toList() }.toSet()
        assertTrue(ids.isNotEmpty())
        ids.forEach { assertTrue(it, doc.contains(it)) }
    }
}
