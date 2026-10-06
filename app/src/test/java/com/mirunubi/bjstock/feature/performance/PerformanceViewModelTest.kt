package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.model.RunStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerformanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakePerformanceDataSource()
    private lateinit var viewModel: PerformanceViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun open(): PerformanceViewModel = PerformanceViewModel(source).also { viewModel = it }

    private val state get() = viewModel.uiState.value

    private fun detail(): PerformanceDetailView = (state.detail as PerformanceDetailState.Loaded).view

    @Test
    fun openingTheScreen_onlyReads_andSelectsTheFirstRunInOrder() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        assertEquals(listOf("runs", "detail:3"), source.reads)
        val rows = (state.runs as PerformanceRunsState.Loaded).rows
        assertEquals(listOf(3L, 4L, 2L, 1L), rows.map { it.runId })
        assertEquals(3L, state.selectedRunId)
        assertEquals("모멘텀 운영", detail().header.name)
        assertFalse(state.comparison.open)
    }

    @Test
    fun viewModel_isStructurallyReadOnly() {
        val sourceFunctions = PerformanceDataSource::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("runs", "detail", "compare"), sourceFunctions)
        val dependencies = PerformanceViewModel::class.java.constructors.single().parameterTypes.toList()
        assertEquals(listOf(PerformanceDataSource::class.java), dependencies)
    }

    @Test
    fun everyRun_isSelectable_andSelectionOnlyReads() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        listOf(4L, 2L, 1L, 3L).forEach { id ->
            viewModel.selectRun(id)
            advanceUntilIdle()
            assertEquals(id, state.selectedRunId)
            assertEquals(source.runs.single { it.run.id == id }.run.runName, detail().header.name)
        }
        assertTrue(source.reads.all { it == "runs" || it.startsWith("detail:") })
        val draft = (state.runs as PerformanceRunsState.Loaded).rows.single { it.runId == 2L }
        assertEquals("설정중", draft.badge.label)
    }

    @Test
    fun refresh_keepsTheSelection_andOnlyReads() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.selectRun(1)
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1L, state.selectedRunId)
        assertEquals("집계 완료", detail().header.statusLabel)
        assertEquals(listOf("runs", "detail:3", "detail:1", "runs", "detail:1"), source.reads)
    }

    @Test
    fun emptyRun_showsInitialCapital() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.selectRun(4)
        advanceUntilIdle()

        val ready = detail() as PerformanceDetailView.Ready
        assertEquals(KeyMetricsView.NotValued("아직 일별 평가 기록이 없습니다.", "100,000,000원"), ready.metrics)
        assertNull(ready.equity)
    }

    @Test
    fun dataErrorRun_showsTheSafeWarning() = runTest(dispatcher) {
        source.runs = source.runs + PerformanceFixtures.runData(7, RunStatus.RUNNING, PerformanceStatus.DATA_ERROR, name = "검증 실패")
        open()
        advanceUntilIdle()
        viewModel.selectRun(7)
        advanceUntilIdle()

        val error = detail() as PerformanceDetailView.DataError
        assertEquals(PerformancePresenter.DATA_ERROR_TITLE, error.title)
        assertFalse(state.toString().contains(PerformanceFixtures.RAW_ERROR))
    }

    @Test
    fun detailFailure_isAFixedMessage_withoutRawText() = runTest(dispatcher) {
        source.failDetail = true
        open()
        advanceUntilIdle()

        assertEquals(PerformanceDetailState.Failed(PerformancePresenter.DETAIL_FAILED), state.detail)
        assertFalse(state.toString().contains("SQLite"))
    }

    @Test
    fun comparison_requiresAtLeastTwoRuns() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        viewModel.openComparison()
        assertEquals(listOf(3L), state.comparison.selected)
        viewModel.compare()
        advanceUntilIdle()

        assertEquals(PerformancePresenter.COMPARE_MIN_MESSAGE, state.comparison.message)
        assertEquals(ComparisonResult.None, state.comparison.result)
        assertTrue(source.compared.isEmpty())
    }

    @Test
    fun comparison_allowsAtMostThreeRuns() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.openComparison()

        viewModel.toggleComparisonRun(1)
        viewModel.toggleComparisonRun(2)
        viewModel.toggleComparisonRun(4)

        assertEquals(listOf(3L, 1L, 2L), state.comparison.selected)
        assertEquals(PerformancePresenter.COMPARE_MAX_MESSAGE, state.comparison.message)
        viewModel.toggleComparisonRun(2)
        viewModel.toggleComparisonRun(4)
        assertEquals(listOf(3L, 1L, 4L), state.comparison.selected)
        assertNull(state.comparison.message)
    }

    @Test
    fun comparison_usesTheExistingCompareRunsService_andShowsPolicyContext() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.openComparison()
        viewModel.toggleComparisonRun(1)
        viewModel.toggleComparisonRun(2)

        viewModel.compare()
        advanceUntilIdle()

        assertEquals(listOf(listOf(3L, 1L, 2L)), source.compared)
        val view = (state.comparison.result as ComparisonResult.Loaded).view
        assertEquals(listOf("지난 운영", "go hbm", "모멘텀 운영"), view.columns.map { it.name })
        assertEquals(listOf("완료", "설정중", "운영 중"), view.columns.map { it.badge!!.label })
        assertEquals("10%", view.columns[0].policy.single { it.label == "1회 매수 비중" }.value)
        assertEquals("운영 준비 전이라 아직 없음", view.columns[1].policy.single().value)
        assertEquals(PerformancePresenter.COMPARE_NOTE, view.note)
        assertTrue(source.reads.none { it.startsWith("write") })
    }

    @Test
    fun changingTheSelection_clearsAStaleResult_andClosingResets() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.openComparison()
        viewModel.toggleComparisonRun(1)
        viewModel.compare()
        advanceUntilIdle()
        assertTrue(state.comparison.result is ComparisonResult.Loaded)

        viewModel.toggleComparisonRun(4)
        assertEquals(ComparisonResult.None, state.comparison.result)

        viewModel.closeComparison()
        assertEquals(ComparisonState(), state.comparison)
        assertEquals(3L, state.selectedRunId)
    }

    // region Comparison layer (PERF-NAV-02): system Back and the top-bar arrow both call closeComparison

    @Test
    fun comparisonLayer_isOpenOnlyWhileComparing() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        assertFalse(state.comparison.open)

        viewModel.openComparison()
        assertTrue(state.comparison.open)

        viewModel.closeComparison()
        assertFalse(state.comparison.open)
    }

    @Test
    fun closingTheComparisonLayer_keepsTheRootRunAndDetail_andOnlyResetsTheComparison() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.selectRun(1)
        advanceUntilIdle()
        val rootDetail = state.detail

        viewModel.openComparison()
        viewModel.toggleComparisonRun(3)
        viewModel.toggleComparisonRun(2)
        viewModel.compare()
        advanceUntilIdle()
        viewModel.toggleComparisonRun(4)
        assertEquals(listOf(1L, 3L, 2L), state.comparison.selected)
        assertEquals(PerformancePresenter.COMPARE_MAX_MESSAGE, state.comparison.message)
        assertTrue(state.comparison.result is ComparisonResult.Loaded)
        val reads = source.reads.toList()

        viewModel.closeComparison()
        advanceUntilIdle()

        assertEquals(ComparisonState(), state.comparison)
        assertEquals(1L, state.selectedRunId)
        assertEquals(rootDetail, state.detail)
        assertEquals(reads, source.reads)
    }

    @Test
    fun reopeningTheComparisonLayer_seedsOnlyTheSelectedRun() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.openComparison()
        viewModel.toggleComparisonRun(1)
        viewModel.closeComparison()
        viewModel.selectRun(4)
        advanceUntilIdle()

        viewModel.openComparison()

        assertEquals(ComparisonState(open = true, selected = listOf(4L)), state.comparison)
    }

    @Test
    fun aCompareStillInFlight_whenTheLayerCloses_doesNotResurrectAResult() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.openComparison()
        viewModel.toggleComparisonRun(1)
        viewModel.compare()
        assertEquals(ComparisonResult.Loading, state.comparison.result)

        viewModel.closeComparison()
        advanceUntilIdle()

        assertEquals(listOf(listOf(3L, 1L)), source.compared)
        assertEquals(ComparisonState(), state.comparison)
        assertEquals(3L, state.selectedRunId)
    }

    // endregion
}
