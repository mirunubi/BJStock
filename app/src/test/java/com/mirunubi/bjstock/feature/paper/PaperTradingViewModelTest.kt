package com.mirunubi.bjstock.feature.paper

import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import java.time.LocalDate
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
class PaperTradingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakePaperTradingDataSource()
    private lateinit var viewModel: PaperTradingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun open(): PaperTradingViewModel = PaperTradingViewModel(source).also { viewModel = it }

    private val state get() = viewModel.uiState.value

    private fun detail(): RunDetailView = (state.detail as DetailState.Loaded).view

    @Test
    fun openingTheScreen_onlyReads_andNeverExecutes() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), source.writes)
        val rows = (state.runs as RunsState.Loaded).rows
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.runId })
        assertEquals(3L, state.selectedRunId)
        assertEquals("모멘텀 운영", detail().header.name)
        val automation = (state.automation as AutomationState.Loaded).view
        assertTrue(automation.enabled)
        assertEquals(3, automation.operations.size)
        assertNull(state.dialog)
    }

    @Test
    fun selectingRuns_onlyReads() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        viewModel.selectRun(2)
        advanceUntilIdle()
        assertEquals("go hbm", detail().header.name)
        assertEquals("설정중", detail().header.badge.label)
        viewModel.selectRun(1)
        advanceUntilIdle()
        assertEquals("완료", detail().header.badge.label)

        assertEquals(emptyList<String>(), source.writes)
        assertTrue("detail:2" in source.reads && "detail:1" in source.reads)
    }

    @Test
    fun localRefresh_onlyReads_andKeepsTheSelection() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.selectRun(1)
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1L, state.selectedRunId)
        assertEquals(emptyList<String>(), source.writes)
    }

    @Test
    fun autoOn_requiresConfirmation_andUsesTheScheduler() = runTest(dispatcher) {
        source.autoStatus = PaperFixtures.autoStatus(enabled = false)
        open()
        advanceUntilIdle()

        viewModel.requestAuto(true)
        assertEquals(PaperDialog.ConfirmAuto(enable = true), state.dialog)
        assertEquals(emptyList<String>(), source.writes)

        viewModel.confirmAuto()
        advanceUntilIdle()
        assertEquals(listOf("setAuto:true"), source.writes)
        assertTrue((state.automation as AutomationState.Loaded).view.enabled)
        assertEquals("자동운영을 켰습니다. 지금 즉시 실행되지는 않습니다.", state.notice?.message)
    }

    @Test
    fun autoOff_requiresConfirmation_andCancelWritesNothing() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        viewModel.requestAuto(false)
        assertEquals(PaperDialog.ConfirmAuto(enable = false), state.dialog)
        viewModel.dismissDialog()
        viewModel.confirmAuto()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), source.writes)

        viewModel.requestAuto(false)
        viewModel.confirmAuto()
        advanceUntilIdle()
        assertEquals(listOf("setAuto:false"), source.writes)
        assertFalse((state.automation as AutomationState.Loaded).view.enabled)
    }

    @Test
    fun runNow_runsOnlyAfterConfirmation() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        viewModel.confirmRunNow()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), source.writes)

        viewModel.requestRunNow()
        assertEquals(PaperDialog.ConfirmRunNow, state.dialog)
        viewModel.dismissDialog()
        assertEquals(emptyList<String>(), source.writes)

        viewModel.requestRunNow()
        viewModel.confirmRunNow()
        advanceUntilIdle()
        assertEquals(listOf("runNow"), source.writes)
        assertEquals("실행 결과: 완료 · 1개 거래일 처리", state.notice?.message)
        assertNull(state.dialog)
    }

    @Test
    fun retry_isOfferedOnlyForARetryableFailedCycle_andRunsAfterConfirmation() = runTest(dispatcher) {
        val failed = PaperFixtures.cycle(9, LocalDate.of(2026, 9, 29), ForwardCycleStatus.FAILED, retryable = true, errorCode = "NETWORK_FAILURE")
        source.details[3] = PaperFixtures.detail(source.runList.single { it.run.id == 3L }, failedCycle = failed)
        open()
        advanceUntilIdle()

        viewModel.requestRetry()
        assertEquals(PaperDialog.ConfirmRetry(3, 9, LocalDate.of(2026, 9, 29)), state.dialog)
        assertEquals(emptyList<String>(), source.writes)
        viewModel.confirmRetry()
        advanceUntilIdle()
        assertEquals(listOf("retry:3:2026-09-29:9"), source.writes)
    }

    @Test
    fun retry_nonRetryableOrMissingFailure_offersNoAction() = runTest(dispatcher) {
        val failed = PaperFixtures.cycle(9, LocalDate.of(2026, 9, 29), ForwardCycleStatus.FAILED, retryable = false, errorCode = "DATA_INTEGRITY_ERROR")
        source.details[3] = PaperFixtures.detail(source.runList.single { it.run.id == 3L }, failedCycle = failed)
        open()
        advanceUntilIdle()

        assertTrue(detail().retry is RetryView.NotRetryable)
        viewModel.requestRetry()
        assertNull(state.dialog)

        viewModel.selectRun(1)
        advanceUntilIdle()
        assertNull(detail().retry)
        viewModel.requestRetry()
        viewModel.confirmRetry()
        advanceUntilIdle()
        assertNull(state.dialog)
        assertEquals(emptyList<String>(), source.writes)
    }

    @Test
    fun newPaperTrading_listsActiveVersions_andCreatesOnlyOnExplicitConfirm() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        viewModel.showCreateDraft()
        advanceUntilIdle()
        val dialog = state.dialog as PaperDialog.CreateDraft
        assertEquals(listOf(VersionOption(2, "기본 모멘텀 전략 V2")), dialog.versions)
        assertEquals(2L, dialog.selectedVersionId)
        assertEquals("2026-10-01", dialog.startDate)
        assertEquals("100000000", dialog.initialCash)
        assertTrue("activeVersions" in source.reads)
        assertEquals(emptyList<String>(), source.writes)

        viewModel.confirmCreateDraft()
        assertEquals(PaperTradingPresenter.NAME_REQUIRED, (state.dialog as PaperDialog.CreateDraft).error)
        viewModel.onDraftName("반도체 모의투자")
        viewModel.onDraftStartDate("2026.10.05")
        viewModel.onDraftInitialCash("50,000,000")
        assertEquals(emptyList<String>(), source.writes)

        viewModel.confirmCreateDraft()
        advanceUntilIdle()
        assertEquals(listOf("createDraft:2:반도체 모의투자:2026-10-05:50000000"), source.writes)
        assertNull(state.dialog)
        assertEquals(4L, state.selectedRunId)
        assertEquals("설정중", detail().header.badge.label)
    }

    @Test
    fun newPaperTrading_withoutActiveVersions_cannotCreate() = runTest(dispatcher) {
        source.versions = emptyList()
        open()
        advanceUntilIdle()

        viewModel.showCreateDraft()
        advanceUntilIdle()
        viewModel.onDraftName("이름")
        viewModel.confirmCreateDraft()
        advanceUntilIdle()

        assertEquals(PaperTradingPresenter.NO_ACTIVE_VERSION, (state.dialog as PaperDialog.CreateDraft).error)
        assertEquals(emptyList<String>(), source.writes)
    }

    @Test
    fun draftUniverse_isEditableThroughTheRunService() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.selectRun(2)
        advanceUntilIdle()

        viewModel.onUniverseQuery("하이닉스")
        advanceUntilIdle()
        assertEquals(listOf(UniverseItem(12, "SK하이닉스 000660")), state.universeSearch.results)
        viewModel.addInstrument(12)
        advanceUntilIdle()
        viewModel.removeInstrument(PaperFixtures.SAMSUNG_ID)
        advanceUntilIdle()
        viewModel.addTheme(5)
        advanceUntilIdle()

        assertEquals(listOf("addInstrument:2:12", "removeInstrument:2:11", "addTheme:2:5"), source.writes)
        assertEquals("테마에서 3종목을 추가했습니다.", state.notice?.message)
    }

    @Test
    fun readyUniverse_isReadOnly() = runTest(dispatcher) {
        open()
        advanceUntilIdle()

        assertFalse(detail().universe.editable)
        viewModel.onUniverseQuery("하이닉스")
        advanceUntilIdle()
        viewModel.addInstrument(12)
        viewModel.removeInstrument(PaperFixtures.SAMSUNG_ID)
        viewModel.addTheme(5)
        viewModel.requestReady()
        advanceUntilIdle()

        assertTrue(state.universeSearch.results.isEmpty())
        assertNull(state.dialog)
        assertEquals(emptyList<String>(), source.writes)
        assertTrue(source.reads.none { it.startsWith("search:") })
    }

    @Test
    fun markReady_showsTheConfirmationSummary_thenCallsTheRunService() = runTest(dispatcher) {
        open()
        advanceUntilIdle()
        viewModel.selectRun(2)
        advanceUntilIdle()

        viewModel.requestReady()
        val dialog = state.dialog as PaperDialog.ConfirmReady
        assertEquals(2L, dialog.runId)
        assertEquals("go hbm", dialog.rows.first().value)
        assertEquals(emptyList<String>(), source.writes)

        viewModel.confirmReady()
        advanceUntilIdle()
        assertEquals(listOf("markReady:2"), source.writes)
        assertEquals("운영 준비를 완료했습니다. 투자 대상과 거래 정책이 고정되었습니다.", state.notice?.message)
    }

    @Test
    fun markReady_failure_showsTheSafeKoreanMessage() = runTest(dispatcher) {
        source.failure = StrategyVersionException(StrategyErrorKind.INVALID_STATE, ForwardErrorCode.EMPTY_UNIVERSE.name)
        open()
        advanceUntilIdle()
        viewModel.selectRun(2)
        advanceUntilIdle()

        viewModel.requestReady()
        viewModel.confirmReady()
        advanceUntilIdle()

        assertEquals(PaperNotice("투자 대상 종목을 하나 이상 추가해 주세요.", isError = true), state.notice)
        assertFalse(state.busy)
    }

    @Test
    fun runNowFailure_isMappedByType_withoutTheExceptionMessage() = runTest(dispatcher) {
        source.failure = IllegalStateException("token=abc /data/data/secret.db")
        open()
        advanceUntilIdle()

        viewModel.requestRunNow()
        viewModel.confirmRunNow()
        advanceUntilIdle()

        val notice = state.notice!!
        assertTrue(notice.isError)
        assertFalse(notice.message.contains("token") || notice.message.contains("/data"))
    }
}
