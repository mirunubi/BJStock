package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.feature.home.HomeFixtures.AUTO_OFF
import com.mirunubi.bjstock.feature.home.HomeFixtures.AUTO_ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresenterTest {
    @Test
    fun noRun_showsEmptyStatesWithoutFabricatedData() {
        val content = HomePresenter.present(HomeFixtures.snapshot(run = null))

        assertEquals(PortfolioCard.NoRun, content.portfolio)
        assertEquals(DecisionCard.Empty("최근 전략 판단 없음"), content.decision)
        assertEquals(HoldingsCard.Empty("실행 중인 모의투자가 없습니다"), content.holdings)
        assertEquals("실행 기록 없음", content.auto.latestOperation)
        assertTrue(content.alerts.isEmpty())
    }

    @Test
    fun runData_isPresentedFromExistingReadModels() {
        val content = HomePresenter.present(HomeFixtures.snapshot())

        val portfolio = content.portfolio as PortfolioCard.Summary
        assertEquals("총 모의자산", portfolio.totalAssetLabel)
        assertEquals("₩100,331,524", portfolio.totalAsset)
        assertEquals("+0.33%", portfolio.cumulativeReturn)
        assertEquals("+₩331,524", portfolio.cumulativeProfit)
        assertEquals("go hbm · Momentum V2", portfolio.runLabel)
        assertEquals("실행중", portfolio.runStatus)
        assertNull(portfolio.otherRunsNote)

        val decision = content.decision as DecisionCard.Latest
        assertEquals("삼성전자 005930", decision.instrument)
        assertEquals("관망", decision.decision)
        assertEquals("HOLD", decision.canonicalDecision)
        assertEquals("56.32", decision.score)
        assertEquals("9월 30일", decision.date)

        val holdings = content.holdings as HoldingsCard.Holdings
        assertEquals(1, holdings.count)
        assertEquals(HoldingRow("삼성전자 005930", "37주", "₩271,000", "+₩333,000"), holdings.items.single())
    }

    @Test
    fun runWithoutValuationOrDecisionOrHoldings_showsSpecificEmptyStates() {
        val data = HomeFixtures.runData(
            summary = HomeFixtures.summary(3, status = PerformanceStatus.EMPTY, totalAsset = null, cumulativeReturn = null),
            positions = emptyList(),
            evaluation = null,
        )
        val content = HomePresenter.present(HomeFixtures.snapshot(run = data))

        val portfolio = content.portfolio as PortfolioCard.Summary
        assertEquals("초기 자본 (아직 평가 전)", portfolio.totalAssetLabel)
        assertEquals("₩100,000,000", portfolio.totalAsset)
        assertEquals("—", portfolio.cumulativeReturn)
        assertEquals(DecisionCard.Empty("최근 전략 판단 없음"), content.decision)
        assertEquals(HoldingsCard.Empty("보유종목 없음"), content.holdings)
    }

    @Test
    fun decisions_areKoreanFirst() {
        mapOf(TradeDecision.BUY to "매수", TradeDecision.SELL to "매도").forEach { (decision, label) ->
            val content = HomePresenter.present(
                HomeFixtures.snapshot(run = HomeFixtures.runData(evaluation = HomeFixtures.evaluation(decision))),
            )
            val card = content.decision as DecisionCard.Latest
            assertEquals(label, card.decision)
            assertEquals(decision.name, card.canonicalDecision)
        }
    }

    @Test
    fun autoOff() {
        val auto = HomePresenter.present(HomeFixtures.snapshot(auto = AUTO_OFF)).auto
        assertFalse(auto.enabled)
        assertEquals("꺼짐", auto.stateLabel)
        assertEquals("자동운영이 꺼져 있습니다", auto.nextRun)
    }

    @Test
    fun autoOn_nextTargetIsEarliestNotExact() {
        val content = HomePresenter.present(HomeFixtures.snapshot(auto = AUTO_ON, operation = HomeFixtures.operation()))
        assertTrue(content.auto.enabled)
        assertEquals("켜짐", content.auto.stateLabel)
        assertEquals("10월 1일 오전 7:00 이후", content.auto.nextRun)
        assertEquals("정상 완료 · 9월 30일 오전 7:34 · 자동", content.auto.latestOperation)
        assertTrue(content.alerts.isEmpty())
    }

    @Test
    fun autoOnWithoutSlot_warns() {
        val content = HomePresenter.present(HomeFixtures.snapshot(auto = AUTO_ON.copy(nextScheduledAt = null)))
        assertEquals("예약 정보 없음", content.auto.nextRun)
        assertEquals(AlertLevel.WARNING, content.alerts.single().level)
    }

    @Test
    fun problems_raiseKoreanAlertsWithoutRawText() {
        val raw = "SQLiteException: near appsecret TEST_APP_SECRET at com.mirunubi.Foo"
        val content = HomePresenter.present(
            HomeFixtures.snapshot(
                run = HomeFixtures.runData(
                    summary = HomeFixtures.summary(3, status = PerformanceStatus.DATA_ERROR, errorMessage = raw),
                ),
                auto = AUTO_ON.copy(lastScheduleFailure = "SCHEDULE_EVENT_PERSIST_FAILED:SQLiteException"),
                operation = HomeFixtures.operation(ForwardOperationStatus.FAILED, finalCode = "NETWORK_FAILURE"),
            ),
        )
        assertEquals(3, content.alerts.size)
        assertTrue(content.alerts.all { it.message.any { c -> c in '\uAC00'..'\uD7A3' } })
        val visible = content.toString()
        listOf(raw, "SQLiteException", "appsecret", "NETWORK_FAILURE", "SCHEDULE_EVENT").forEach {
            assertFalse(it, visible.contains(it))
        }
    }

    @Test
    fun financialIntegrityCode_isAProminentError() {
        val content = HomePresenter.present(
            HomeFixtures.snapshot(operation = HomeFixtures.operation(ForwardOperationStatus.BLOCKED, "LEDGER_MISMATCH")),
        )
        val alert = content.alerts.single()
        assertEquals(AlertLevel.ERROR, alert.level)
        assertTrue(alert.message.startsWith("재무 무결성 오류"))
        assertFalse(alert.message.contains("LEDGER_MISMATCH"))
    }

    @Test
    fun otherCandidateRuns_areNotedNotAggregated() {
        val content = HomePresenter.present(HomeFixtures.snapshot(candidates = 3))
        assertEquals("다른 모의투자 2개는 모의투자 탭에서 확인", (content.portfolio as PortfolioCard.Summary).otherRunsNote)
    }

    @Test
    fun runSelection_prefersRunningThenReadyThenPausedThenCompleted_thenHighestId() {
        val runs = listOf(
            HomeFixtures.run(1, RunStatus.COMPLETED),
            HomeFixtures.run(2, RunStatus.READY),
            HomeFixtures.run(3, RunStatus.DRAFT),
            HomeFixtures.run(4, RunStatus.READY),
            HomeFixtures.run(5, RunStatus.CANCELLED),
            HomeFixtures.run(6, RunStatus.PAUSED),
        )
        assertEquals(4L, HomeRunSelection.select(runs)?.id)
        assertEquals(listOf(4L, 2L, 6L, 1L), HomeRunSelection.candidates(runs).map { it.id })
        assertEquals(7L, HomeRunSelection.select(runs + HomeFixtures.run(7, RunStatus.RUNNING))?.id)
        assertNull(HomeRunSelection.select(listOf(HomeFixtures.run(1, RunStatus.DRAFT), HomeFixtures.run(2, RunStatus.CANCELLED))))
    }
}
