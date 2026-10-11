package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.ui.navigation.NavWidthClass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerformanceLayoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakePerformanceDataSource()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region mode

    @Test
    fun compact_isSinglePane() {
        assertEquals(PerformanceLayoutMode.SINGLE_PANE, PerformanceLayout.modeFor(NavWidthClass.COMPACT))
    }

    @Test
    fun medium_isSinglePane() {
        assertEquals(PerformanceLayoutMode.SINGLE_PANE, PerformanceLayout.modeFor(NavWidthClass.MEDIUM))
    }

    @Test
    fun expanded_isListDetail() {
        assertEquals(PerformanceLayoutMode.LIST_DETAIL, PerformanceLayout.modeFor(NavWidthClass.EXPANDED))
    }

    // endregion

    // region sections

    @Test
    fun singlePane_keepsTheCurrentOrder() {
        assertEquals(listOf(PerformanceSection.RUN_SELECTOR, PerformanceSection.DETAIL), PerformanceLayout.singlePane)
    }

    @Test
    fun listDetail_leftHoldsOnlyTheRunSelector() {
        assertEquals(listOf(PerformanceSection.RUN_SELECTOR), PerformanceLayout.left)
        assertEquals(360, PerformanceLayout.LEFT_PANE_WIDTH_DP)
    }

    @Test
    fun listDetail_rightHoldsOnlyTheDetail() {
        assertEquals(listOf(PerformanceSection.DETAIL), PerformanceLayout.right)
    }

    @Test
    fun listDetail_coversEverySectionExactlyOnce() {
        val placed = PerformanceLayout.left + PerformanceLayout.right
        assertEquals(placed.size, placed.toSet().size)
        assertEquals(PerformanceSection.entries.toSet(), placed.toSet())
        assertEquals(PerformanceLayout.singlePane, placed)
    }

    // endregion

    // region right pane

    @Test
    fun noDetail_rightPaneIsBlank() {
        assertEquals(PerformanceRightPane.Blank, PerformanceLayout.rightPane(PerformanceDetailState.None))
    }

    @Test
    fun loading_rightPaneShowsTheDetail() {
        assertEquals(
            PerformanceRightPane.Detail(PerformanceDetailState.Loading),
            PerformanceLayout.rightPane(PerformanceDetailState.Loading),
        )
    }

    @Test
    fun failed_rightPaneShowsTheDetail() = runTest(dispatcher) {
        source.failDetail = true
        val viewModel = PerformanceViewModel(source)
        advanceUntilIdle()
        val failed = viewModel.uiState.value.detail
        assertTrue(failed is PerformanceDetailState.Failed)
        assertEquals(PerformanceRightPane.Detail(failed), PerformanceLayout.rightPane(failed))
    }

    @Test
    fun loaded_rightPaneShowsTheDetail() = runTest(dispatcher) {
        val viewModel = PerformanceViewModel(source)
        advanceUntilIdle()
        val loaded = viewModel.uiState.value.detail
        assertTrue(loaded is PerformanceDetailState.Loaded)
        assertEquals(PerformanceRightPane.Detail(loaded), PerformanceLayout.rightPane(loaded))
    }

    @Test
    fun rightPaneKey_followsTheSelectedRun() {
        assertEquals("none", PerformanceLayout.rightPaneKey(null))
        assertEquals("run:3", PerformanceLayout.rightPaneKey(3))
        assertEquals(PerformanceLayout.rightPaneKey(2), PerformanceLayout.rightPaneKey(2))
        assertNotEquals(PerformanceLayout.rightPaneKey(2), PerformanceLayout.rightPaneKey(3))
    }

    @Test
    fun layout_addsNoSelectionState() {
        val performanceDir = performanceDir()
        val layout = File(performanceDir, "PerformanceLayout.kt").readText()
        listOf("var ", "mutableStateOf", "remember", "selectRun").forEach {
            assertFalse("PerformanceLayout.kt: $it", layout.contains(it))
        }
        val screen = File(performanceDir, "PerformanceScreen.kt").readText()
        listOf("mutableStateOf", "rememberSaveable", "remember {", "remember(").forEach {
            assertFalse("PerformanceScreen.kt: $it", screen.contains(it))
        }
        assertEquals(2, Regex("""state\.selectedRunId""").findAll(screen).count())
        assertTrue(screen.contains("RunRowItem(row, selected = row.runId == state.selectedRunId) { viewModel.selectRun(row.runId) }"))
        assertTrue(screen.contains("key(PerformanceLayout.rightPaneKey(state.selectedRunId))"))
    }

    @Test
    fun layout_isAPurePolicyWithoutDataAccess() {
        val layout = File(performanceDir(), "PerformanceLayout.kt").readText()
        val imports = layout.lines().filter { it.startsWith("import ") }
        assertEquals(listOf("import com.mirunubi.bjstock.ui.navigation.NavWidthClass"), imports)
        listOf("ViewModel", "DataSource", "Dao", "Repository", "suspend ").forEach {
            assertFalse("PerformanceLayout.kt: $it", layout.contains(it))
        }
    }

    // endregion

    // region real ViewModel

    @Test
    fun defaultSelection_isUnchanged() = runTest(dispatcher) {
        val viewModel = PerformanceViewModel(source)
        advanceUntilIdle()
        assertEquals(PerformancePresenter.defaultSelection(source.runs), viewModel.uiState.value.selectedRunId)
        assertEquals(3L, viewModel.uiState.value.selectedRunId)
        assertEquals("run:3", PerformanceLayout.rightPaneKey(viewModel.uiState.value.selectedRunId))
        assertEquals(listOf("runs", "detail:3"), source.reads)
    }

    @Test
    fun selectRun_behavesAsBefore() = runTest(dispatcher) {
        val viewModel = PerformanceViewModel(source)
        advanceUntilIdle()

        viewModel.selectRun(2)
        assertEquals(2L, viewModel.uiState.value.selectedRunId)
        assertEquals(PerformanceDetailState.Loading, viewModel.uiState.value.detail)
        assertEquals(
            PerformanceRightPane.Detail(PerformanceDetailState.Loading),
            PerformanceLayout.rightPane(viewModel.uiState.value.detail),
        )
        advanceUntilIdle()
        assertEquals("go hbm", (viewModel.uiState.value.detail as PerformanceDetailState.Loaded).view.header.name)
        assertEquals(listOf("runs", "detail:3", "detail:2"), source.reads)

        viewModel.selectRun(2)
        advanceUntilIdle()
        assertEquals(listOf("runs", "detail:3", "detail:2"), source.reads)
    }

    @Test
    fun openingAndClosingTheComparison_keepsTheSelectionAndReadsNothing() = runTest(dispatcher) {
        val viewModel = PerformanceViewModel(source)
        advanceUntilIdle()
        val selected = viewModel.uiState.value.selectedRunId
        val detail = viewModel.uiState.value.detail
        val reads = source.reads.toList()

        viewModel.openComparison()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.comparison.open)
        viewModel.closeComparison()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.comparison.open)
        assertEquals(selected, viewModel.uiState.value.selectedRunId)
        assertEquals(detail, viewModel.uiState.value.detail)
        assertEquals(reads, source.reads)
    }

    // endregion

    // region source pins

    @Test
    fun screen_readsTheSharedWidthClassOnly() {
        val screen = File(performanceDir(), "PerformanceScreen.kt").readText()
        assertTrue(screen.contains("PerformanceLayout.modeFor(LocalNavChrome.current.widthClass)"))
        assertTrue(screen.contains("PaneColumn(Modifier.width(PerformanceLayout.LEFT_PANE_WIDTH_DP.dp))"))
        assertTrue(screen.contains("PaneColumn(Modifier.weight(1f))"))
        assertTrue(screen.contains("horizontalArrangement = Arrangement.spacedBy(16.dp)"))
    }

    @Test
    fun expandedComparison_replacesBothPanesFullWidth() {
        val screen = File(performanceDir(), "PerformanceScreen.kt").readText()
        assertEquals(2, Regex("""ComparisonSection\(state, viewModel\)""").findAll(screen).count())
        val fullWidth = screen.indexOf("layered -> ScrollColumn { ComparisonSection(state, viewModel) }")
        val split = screen.indexOf("else -> Row(")
        assertTrue(fullWidth >= 0)
        assertTrue(fullWidth < split)
        val panes = screen.substring(split, screen.indexOf("private fun PerformanceSectionContent("))
        assertFalse(panes.contains("ComparisonSection("))
        assertFalse(panes.contains("openComparison"))
    }

    @Test
    fun comparisonButton_staysInTheRunSelectorHeader() {
        val screen = File(performanceDir(), "PerformanceScreen.kt").readText()
        assertEquals(1, Regex("""viewModel::openComparison""").findAll(screen).count())
        val selector = screen.substring(
            screen.indexOf("private fun RunSelectorSection("),
            screen.indexOf("private fun RunRowItem("),
        )
        assertTrue(selector.contains("OutlinedButton(onClick = viewModel::openComparison"))
    }

    @Test
    fun backSemantics_areThoseOfPerfNav02() {
        val screen = File(performanceDir(), "PerformanceScreen.kt").readText()
        assertTrue(screen.contains("val layered = state.comparison.open"))
        assertEquals(1, Regex("""LayerBackHandler\(""").findAll(screen).count())
        assertTrue(screen.contains("LayerBackHandler(hasInScreenLayer = layered) { viewModel.closeComparison() }"))
        assertTrue(screen.contains("onBack = if (layered) viewModel::closeComparison else null"))
        assertTrue(screen.contains("TextButton(onClick = viewModel::closeComparison"))
    }

    @Test
    fun performanceSources_addNoRouteAndNoBreakpoint() {
        val performanceDir = performanceDir()
        listOf("PerformanceScreen.kt", "PerformanceLayout.kt").forEach { name ->
            val text = File(performanceDir, name).readText()
            listOf("navigate(", "popBackStack(", "navigateToTab(", "BJStockRoutes", "NavHost").forEach {
                assertFalse("$name: $it", text.contains(it))
            }
        }
        performanceDir.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            listOf("fromWidthDp(", "currentNavWidthClass(", "screenWidthDp", "material3.adaptive").forEach {
                assertFalse("${file.name}: $it", text.contains(it))
            }
            assertFalse("${file.name}: BackHandler", Regex("""(?<![A-Za-z])BackHandler\(""").containsMatchIn(text))
            assertFalse(file.name, Regex("""\b(600|840)\b""").containsMatchIn(text))
        }
    }

    // endregion

    private fun performanceDir(): File = listOf(
        File("src/main/java/com/mirunubi/bjstock/feature/performance"),
        File("app/src/main/java/com/mirunubi/bjstock/feature/performance"),
    ).first { it.isDirectory }
}
