package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.strategy.StrategyActivationFailure
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StrategyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeStrategyDataSource()
    private lateinit var viewModel: StrategyViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = StrategyViewModel(source)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun opening_listsStrategies_andWritesNothing() = runTest(dispatcher) {
        advanceUntilIdle()

        val list = viewModel.uiState.value.list as ListState.Loaded
        assertEquals("기본 모멘텀 전략", list.cards.single().name)
        assertEquals("사용중 2개 · 작성중 1개 · 종료 1개", list.cards.single().statusSummary)
        assertNull(viewModel.uiState.value.strategy)
        assertNull(viewModel.uiState.value.dialog)

        openVersion(20)
        viewModel.toggleFactorAdvanced(FactorCodes.VOLATILITY_20D)
        advanceUntilIdle()
        assertTrue(source.writes.isEmpty())
        assertTrue(source.previewCalls.isEmpty())
    }

    @Test
    fun selectingAStrategy_showsVersions_highestFirst_withMultipleActive() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()

        val rows = (viewModel.uiState.value.strategy!!.versions as VersionsState.Loaded).rows
        assertEquals(listOf("V4", "V3", "V2", "V1"), rows.map { it.label })
        assertEquals(listOf("종료", "작성중", "사용중", "사용중"), rows.map { it.badge.label })
    }

    @Test
    fun draftVersion_isEditable_andShowsNoLockReason() = runTest(dispatcher) {
        val panel = openVersion(30)
        assertTrue(panel.editable)
        assertNull(panel.lockedReason)

        viewModel.onSellChange("35")
        viewModel.onFactorWeight(FactorCodes.MOMENTUM_20D, "30")
        viewModel.startNewRule()
        val edited = loadedPanel()
        assertEquals("35", edited.sellInput)
        assertEquals("30", edited.factors.single { it.code == FactorCodes.MOMENTUM_20D }.weightPercent)
        assertNotNull(edited.ruleForm)
        assertTrue(edited.hasUnsavedChanges)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun activeVersion_isReadOnly_withTheKoreanReason_andIgnoresEdits() = runTest(dispatcher) {
        val panel = openVersion(20)
        assertFalse(panel.editable)
        assertEquals("사용 중인 버전은 수정할 수 없습니다.\n변경하려면 새 작성본을 만드세요.", panel.lockedReason)

        viewModel.onSellChange("10")
        viewModel.onFactorEnabled(FactorCodes.MOMENTUM_20D, false)
        viewModel.startNewRule()
        viewModel.saveThresholds()
        viewModel.saveFactors()
        viewModel.requestActivate()
        viewModel.requestDeleteRule(101)
        advanceUntilIdle()

        assertEquals(panel.copy(), loadedPanel())
        assertNull(viewModel.uiState.value.dialog)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun retiredVersion_isReadOnly_withTheKoreanReason() = runTest(dispatcher) {
        val panel = openVersion(40)
        assertFalse(panel.editable)
        assertEquals("종료된 버전은 수정할 수 없습니다.", panel.lockedReason)

        viewModel.onBuyChange("90")
        viewModel.requestActivate()
        advanceUntilIdle()
        assertEquals("70", loadedPanel().buyInput)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun newStrategy_needsTheDialogAndConfirm_validatesInKorean_thenOpensItsDraft() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.showCreateStrategy()
        viewModel.confirmCreateStrategy()
        assertEquals(StrategyPresenter.NAME_REQUIRED, (viewModel.uiState.value.dialog as StrategyDialog.CreateStrategy).error)

        viewModel.onCreateNameChange("가치 전략")
        viewModel.onCreateCodeChange("MOMENTUM_BASIC")
        viewModel.confirmCreateStrategy()
        advanceUntilIdle()
        assertEquals(StrategyPresenter.CODE_EXISTS, (viewModel.uiState.value.dialog as StrategyDialog.CreateStrategy).error)
        assertTrue(source.writes.isEmpty())

        viewModel.onCreateCodeChange("VALUE_BASIC")
        viewModel.confirmCreateStrategy()
        advanceUntilIdle()

        assertEquals(listOf("createStrategy:VALUE_BASIC"), source.writes)
        val state = viewModel.uiState.value
        assertNull(state.dialog)
        assertEquals("가치 전략", state.strategy!!.name)
        val panel = (state.version as VersionLayer.Loaded).panel
        assertEquals("V1", panel.label)
        assertEquals("작성중", panel.badge.label)
    }

    @Test
    fun newDraft_andCopy_callTheServiceOnlyOnTheirButtons() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()
        assertTrue(source.writes.isEmpty())

        viewModel.createDraft()
        advanceUntilIdle()
        assertEquals("createDraft:1", source.writes.last())
        assertEquals("V5", loadedPanel().label)

        viewModel.openVersion(20)
        advanceUntilIdle()
        viewModel.copyVersion()
        advanceUntilIdle()
        assertEquals("copyToDraft:20", source.writes.last())
        val copy = loadedPanel()
        assertEquals("작성중", copy.badge.label)
        assertTrue(copy.editable)
        assertEquals("V2를 복사해 새 작성본을 만들었습니다.", viewModel.uiState.value.notice!!.message)
    }

    @Test
    fun activation_asksForConfirmationFirst_thenUsesTheService() = runTest(dispatcher) {
        openVersion(30)

        viewModel.requestActivate()
        val dialog = viewModel.uiState.value.dialog as StrategyDialog.ConfirmActivate
        assertEquals("V3을 사용 시작하시겠습니까?", dialog.title)
        assertTrue("기본 모멘텀 전략" in dialog.body)
        assertTrue(source.writes.isEmpty())

        viewModel.dismissDialog()
        assertTrue(source.writes.isEmpty())

        viewModel.requestActivate()
        viewModel.confirmActivate()
        advanceUntilIdle()
        assertEquals(listOf("activate:30"), source.writes)
        val panel = loadedPanel()
        assertEquals("사용중", panel.badge.label)
        assertFalse(panel.editable)
        assertEquals("V3 사용을 시작했습니다.", viewModel.uiState.value.notice!!.message)
    }

    @Test
    fun activation_isBlockedWhileChangesAreUnsaved() = runTest(dispatcher) {
        openVersion(30)
        viewModel.onFactorWeight(FactorCodes.MOMENTUM_20D, "15")

        viewModel.requestActivate()

        assertNull(viewModel.uiState.value.dialog)
        assertEquals(StrategyPresenter.UNSAVED_BLOCKS_ACTIVATION, viewModel.uiState.value.notice!!.message)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun activationFailure_showsTheSafeKoreanMapping_notTheRawMessage() = runTest(dispatcher) {
        source.activationResult = StrategyActivationResult.Failed(
            StrategyActivationFailure.CONFLICTING_SIGNAL_RULES,
            "conflicting actions at priority 10: BUY, SELL",
        )
        openVersion(30)
        viewModel.requestActivate()
        viewModel.confirmActivate()
        advanceUntilIdle()

        val notice = viewModel.uiState.value.notice!!
        assertTrue(notice.isError)
        assertEquals("서로 충돌하는 신호 규칙이 있어 사용할 수 없습니다. 신호 규칙을 확인해 주세요.", notice.message)
        assertFalse("priority" in notice.message)
        assertEquals("작성중", loadedPanel().badge.label)
    }

    @Test
    fun serviceExceptions_areShownAsFixedKoreanText() = runTest(dispatcher) {
        openVersion(30)
        source.writeFailure = StrategyVersionException(StrategyErrorKind.INVALID_THRESHOLDS, "sell_threshold must be less than buy_threshold (id 30)")
        viewModel.onSellChange("80")
        viewModel.saveThresholds()
        advanceUntilIdle()

        val notice = viewModel.uiState.value.notice!!
        assertEquals("매도 기준은 매수 기준보다 낮아야 하며, 0~100 사이여야 합니다.", notice.message)
        assertFalse("sell_threshold" in notice.message)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test
    fun savingThresholds_factors_andRules_goThroughTheService_withStoredScales() = runTest(dispatcher) {
        openVersion(30)
        viewModel.onSellChange("35")
        viewModel.onBuyChange("75")
        viewModel.saveThresholds()
        advanceUntilIdle()
        assertEquals("saveThresholds:30:350000:750000", source.writes.last())
        assertEquals("35", loadedPanel().savedSell)

        viewModel.onFactorWeight(FactorCodes.MOMENTUM_20D, "15")
        viewModel.toggleFactorAdvanced(FactorCodes.MOMENTUM_20D)
        viewModel.onFactorMin(FactorCodes.MOMENTUM_20D, "20")
        viewModel.saveFactors()
        advanceUntilIdle()
        val saved = source.savedWeights.single()
        assertEquals(FactorCodes.MOMENTUM_20D, saved.factorCode)
        assertEquals(150_000L, saved.weightStored)
        assertEquals(200_000L, saved.minScoreStored)

        viewModel.startNewRule()
        viewModel.onRuleCode("BIG_DROP")
        viewModel.onRuleThreshold("-8")
        viewModel.onRuleOperator(SignalOperator.LTE)
        viewModel.onRuleAction(SignalAction.BUY)
        viewModel.onRulePriority("5")
        viewModel.saveRule()
        advanceUntilIdle()
        assertEquals("saveRule:30:BIG_DROP:LTE:-8:BUY:5", source.writes.last())

        viewModel.requestDeleteRule(101)
        assertEquals(StrategyDialog.ConfirmDeleteRule(101, "DIP_BUY"), viewModel.uiState.value.dialog)
        viewModel.confirmDeleteRule()
        advanceUntilIdle()
        assertEquals("deleteRule:101", source.writes.last())
    }

    @Test
    fun preview_searchSelectDate_andRun_showsKoreanResult_withoutWrites() = runTest(dispatcher) {
        source.previewResult = StrategyEvaluationResult(
            status = StrategyEvaluationStatus.SUCCESS,
            quantScoreStored = 0L,
            quantDecision = TradeDecision.SELL,
            decisionSource = DecisionSource.SIGNAL_RULE,
            triggeredRuleId = 102,
            observedValue = "3.40",
            thresholdValue = "3.00",
        )
        openVersion(20)

        viewModel.onPreviewQuery("삼성")
        advanceUntilIdle()
        val found = (loadedPanel().preview.search as PreviewSearch.Results).instruments.single()
        viewModel.selectPreviewInstrument(found)
        advanceUntilIdle()
        assertEquals("2026-09-29", loadedPanel().preview.dateInput)
        assertEquals(2, loadedPanel().preview.dates.size)

        viewModel.pickPreviewDate(LocalDate.of(2026, 9, 26))
        viewModel.runPreview()
        advanceUntilIdle()

        assertEquals(Triple(20L, 11L, LocalDate.of(2026, 9, 26)), source.previewCalls.single())
        val view = (loadedPanel().preview.result as PreviewResultState.Shown).view
        assertEquals("매도", view.decisionLabel)
        assertEquals("신호 규칙", view.sourceLabel)
        assertNull(view.score)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun preview_rejectsAMalformedDate_inKorean() = runTest(dispatcher) {
        openVersion(20)
        viewModel.selectPreviewInstrument(StrategyFixtures.SAMSUNG)
        advanceUntilIdle()
        viewModel.onPreviewDate("9월 29일")
        viewModel.runPreview()

        assertEquals(PreviewResultState.Failed(StrategyPresenter.PREVIEW_DATE_INVALID), loadedPanel().preview.result)
        assertTrue(source.previewCalls.isEmpty())
    }

    @Test
    fun back_closesVersion_thenStrategy_thenReportsRoot() = runTest(dispatcher) {
        openVersion(20)
        assertTrue(viewModel.back())
        assertNull(viewModel.uiState.value.version)
        assertNotNull(viewModel.uiState.value.strategy)
        assertTrue(viewModel.back())
        assertNull(viewModel.uiState.value.strategy)
        assertFalse(viewModel.back())
    }

    private fun TestScope.openVersion(versionId: Long): VersionPanel {
        advanceUntilIdle()
        viewModel.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()
        viewModel.openVersion(versionId)
        advanceUntilIdle()
        return loadedPanel()
    }

    private fun loadedPanel(): VersionPanel = (viewModel.uiState.value.version as VersionLayer.Loaded).panel
}
