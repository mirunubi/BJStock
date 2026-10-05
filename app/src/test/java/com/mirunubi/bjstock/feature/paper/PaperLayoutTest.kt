package com.mirunubi.bjstock.feature.paper

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
class PaperLayoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakePaperTradingDataSource()

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
        assertEquals(PaperLayoutMode.SINGLE_PANE, PaperLayout.modeFor(NavWidthClass.COMPACT))
    }

    @Test
    fun medium_isSinglePane() {
        assertEquals(PaperLayoutMode.SINGLE_PANE, PaperLayout.modeFor(NavWidthClass.MEDIUM))
    }

    @Test
    fun expanded_isListDetail() {
        assertEquals(PaperLayoutMode.LIST_DETAIL, PaperLayout.modeFor(NavWidthClass.EXPANDED))
    }

    // endregion

    // region sections

    @Test
    fun singlePane_keepsTheCurrentOrder() {
        assertEquals(
            listOf(
                PaperSection.NOTICE,
                PaperSection.RUN_LIST,
                PaperSection.AUTOMATION,
                PaperSection.DETAIL,
                PaperSection.RECENT_OPERATIONS,
            ),
            PaperLayout.singlePane,
        )
    }

    @Test
    fun listDetail_leftHoldsTheListAutomationAndRecentOperations() {
        assertEquals(listOf(PaperSection.NOTICE), PaperLayout.fullWidth)
        assertEquals(
            listOf(PaperSection.RUN_LIST, PaperSection.AUTOMATION, PaperSection.RECENT_OPERATIONS),
            PaperLayout.left,
        )
        assertEquals(360, PaperLayout.LEFT_PANE_WIDTH_DP)
    }

    @Test
    fun listDetail_rightHoldsOnlyTheDetail() {
        assertEquals(listOf(PaperSection.DETAIL), PaperLayout.right)
    }

    @Test
    fun listDetail_coversEverySectionExactlyOnce() {
        val placed = PaperLayout.fullWidth + PaperLayout.left + PaperLayout.right
        assertEquals(placed.size, placed.toSet().size)
        assertEquals(PaperSection.entries.toSet(), placed.toSet())
        assertEquals(PaperLayout.singlePane.toSet(), placed.toSet())
        assertEquals(PaperLayout.singlePane.size, placed.size)
    }

    // endregion

    // region right pane

    @Test
    fun noRun_rightPaneIsBlank() {
        assertEquals(PaperRightPane.Blank, PaperLayout.rightPane(DetailState.None))
    }

    @Test
    fun loading_rightPaneShowsTheDetail() {
        assertEquals(PaperRightPane.Detail(DetailState.Loading), PaperLayout.rightPane(DetailState.Loading))
    }

    @Test
    fun failed_rightPaneShowsTheDetail() {
        val failed = DetailState.Failed("실패")
        assertEquals(PaperRightPane.Detail(failed), PaperLayout.rightPane(failed))
    }

    @Test
    fun loaded_rightPaneShowsTheDetail() = runTest(dispatcher) {
        val viewModel = PaperTradingViewModel(source)
        advanceUntilIdle()
        val loaded = viewModel.uiState.value.detail
        assertTrue(loaded is DetailState.Loaded)
        assertEquals(PaperRightPane.Detail(loaded), PaperLayout.rightPane(loaded))
    }

    @Test
    fun rightPaneKey_followsTheSelectedRun() {
        assertEquals("none", PaperLayout.rightPaneKey(null))
        assertEquals("run:3", PaperLayout.rightPaneKey(3))
        assertEquals(PaperLayout.rightPaneKey(2), PaperLayout.rightPaneKey(2))
        assertNotEquals(PaperLayout.rightPaneKey(2), PaperLayout.rightPaneKey(3))
    }

    @Test
    fun layout_addsNoSelectionState() {
        val paperDir = paperDir()
        val layout = File(paperDir, "PaperLayout.kt").readText()
        listOf("var ", "mutableStateOf", "remember", "selectRun").forEach {
            assertFalse("PaperLayout.kt: $it", layout.contains(it))
        }
        val screen = File(paperDir, "PaperTradingScreen.kt").readText()
        listOf("mutableStateOf", "rememberSaveable", "remember {", "remember(").forEach {
            assertFalse("PaperTradingScreen.kt: $it", screen.contains(it))
        }
        assertEquals(2, Regex("""state\.selectedRunId""").findAll(screen).count())
        assertTrue(screen.contains("RunRowItem(row, selected = row.runId == state.selectedRunId) { viewModel.selectRun(row.runId) }"))
        assertTrue(screen.contains("key(PaperLayout.rightPaneKey(state.selectedRunId))"))
    }

    // endregion

    // region real ViewModel

    @Test
    fun defaultSelection_isUnchanged() = runTest(dispatcher) {
        val viewModel = PaperTradingViewModel(source)
        advanceUntilIdle()
        assertEquals(PaperTradingPresenter.defaultSelection(source.runList), viewModel.uiState.value.selectedRunId)
        assertEquals(3L, viewModel.uiState.value.selectedRunId)
        assertEquals("run:3", PaperLayout.rightPaneKey(viewModel.uiState.value.selectedRunId))
    }

    @Test
    fun selectRun_behavesAsBefore() = runTest(dispatcher) {
        val viewModel = PaperTradingViewModel(source)
        advanceUntilIdle()

        viewModel.selectRun(2)
        assertEquals(2L, viewModel.uiState.value.selectedRunId)
        assertEquals(DetailState.Loading, viewModel.uiState.value.detail)
        assertEquals(PaperRightPane.Detail(DetailState.Loading), PaperLayout.rightPane(viewModel.uiState.value.detail))
        advanceUntilIdle()
        assertEquals("go hbm", (viewModel.uiState.value.detail as DetailState.Loaded).view.header.name)

        val reads = source.reads.size
        viewModel.selectRun(2)
        advanceUntilIdle()
        assertEquals(reads, source.reads.size)
    }

    @Test
    fun changingTheSelection_writesNothing() = runTest(dispatcher) {
        val viewModel = PaperTradingViewModel(source)
        advanceUntilIdle()
        listOf(2L, 1L, 3L, 1L).forEach {
            viewModel.selectRun(it)
            advanceUntilIdle()
        }
        assertEquals(emptyList<String>(), source.writes)
    }

    @Test
    fun changingTheSelection_leavesAutomationUntouched() = runTest(dispatcher) {
        val viewModel = PaperTradingViewModel(source)
        advanceUntilIdle()
        val automation = viewModel.uiState.value.automation
        val automationReads = source.reads.count { it == "automation" }
        listOf(2L, 1L).forEach {
            viewModel.selectRun(it)
            advanceUntilIdle()
        }
        assertEquals(automation, viewModel.uiState.value.automation)
        assertEquals(automationReads, source.reads.count { it == "automation" })
    }

    // endregion

    @Test
    fun paperSources_keepTheShellAndSelectionContract() {
        val paperDir = paperDir()
        val screen = File(paperDir, "PaperTradingScreen.kt").readText()
        assertTrue(screen.contains("PaperLayout.modeFor(LocalNavChrome.current.widthClass)"))
        assertEquals(1, Regex("""Dialogs\(state, viewModel\)""").findAll(screen).count())
        assertTrue(
            File(paperDir, "PaperTradingViewModel.kt").readText()
                .contains("?: PaperTradingPresenter.defaultSelection(runs)"),
        )

        paperDir.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            listOf("fromWidthDp(", "currentNavWidthClass(", "screenWidthDp", "material3.adaptive", "BackHandler").forEach {
                assertFalse("${file.name}: $it", text.contains(it))
            }
            assertFalse(file.name, Regex("""\b(600|840)\b""").containsMatchIn(text))
        }
    }

    private fun paperDir(): File = listOf(
        File("src/main/java/com/mirunubi/bjstock/feature/paper"),
        File("app/src/main/java/com/mirunubi/bjstock/feature/paper"),
    ).first { it.isDirectory }
}
