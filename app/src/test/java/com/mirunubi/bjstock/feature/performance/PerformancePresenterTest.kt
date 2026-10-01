package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.feature.performance.PerformanceFixtures.point
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresenterTest {
    private fun ready(data: PerformanceRunData, detail: PerformanceDetailData = PerformanceFixtures.detail(data)) =
        PerformancePresenter.detail(detail) as PerformanceDetailView.Ready

    private fun inProgress() = PerformanceFixtures.runData(3, RunStatus.RUNNING, name = "모멘텀 운영")

    private fun stat(rows: List<StatRow>, label: String) = rows.single { it.label == label }.value

    // region Run selector

    @Test
    fun runStatuses_useTheCanonicalProductWording() {
        assertEquals(
            mapOf(
                RunStatus.DRAFT to "설정중",
                RunStatus.READY to "실행 준비",
                RunStatus.RUNNING to "운영 중",
                RunStatus.PAUSED to "일시정지",
                RunStatus.COMPLETED to "완료",
                RunStatus.CANCELLED to "취소",
            ),
            RunStatus.entries.associateWith { PerformancePresenter.badge(it).label },
        )
    }

    @Test
    fun performanceStatuses_areTranslated_enumsStayInternal() {
        assertEquals(
            mapOf(
                PerformanceStatus.EMPTY to "평가 전",
                PerformanceStatus.IN_PROGRESS to "진행 중",
                PerformanceStatus.COMPLETE to "집계 완료",
                PerformanceStatus.DATA_ERROR to "데이터 확인 필요",
            ),
            PerformanceStatus.entries.associateWith(PerformancePresenter::performanceStatus),
        )
    }

    @Test
    fun runSelector_listsEveryRun_inThePaperTradingOrder_thenNewestFirst() {
        val runs = listOf(
            PerformanceFixtures.runData(1, RunStatus.COMPLETED, PerformanceStatus.COMPLETE),
            PerformanceFixtures.runData(2, RunStatus.DRAFT, PerformanceStatus.EMPTY),
            PerformanceFixtures.runData(3, RunStatus.CANCELLED, PerformanceStatus.COMPLETE),
            PerformanceFixtures.runData(4, RunStatus.READY, PerformanceStatus.EMPTY),
            PerformanceFixtures.runData(5, RunStatus.PAUSED),
            PerformanceFixtures.runData(6, RunStatus.RUNNING),
            PerformanceFixtures.runData(7, RunStatus.RUNNING),
        )

        val rows = PerformancePresenter.runRows(runs)

        assertEquals(listOf(7L, 6L, 4L, 5L, 2L, 1L, 3L), rows.map { it.runId })
        assertEquals(7L, PerformancePresenter.defaultSelection(runs))
    }

    @Test
    fun runRow_showsNameStrategyStatusPeriodAndAsset_withoutDatabaseIds() {
        val rows = PerformancePresenter.runRows(
            listOf(inProgress(), PerformanceFixtures.runData(2, RunStatus.DRAFT, PerformanceStatus.EMPTY, name = "go hbm")),
        )
        val running = rows.first()
        assertEquals("모멘텀 운영", running.name)
        assertEquals("기본 모멘텀 전략 · V2", running.strategyLabel)
        assertEquals("운영 중", running.badge.label)
        assertEquals("2026.09.18 ~ 2026.09.30", running.period)
        assertEquals("총 모의자산 100,331,524원 · +0.33%", running.assetSummary)

        val draft = rows.last()
        assertEquals("2026.09.18부터", draft.period)
        assertEquals("초기자금 100,000,000원 · 평가 전", draft.assetSummary)
        assertFalse(rows.any { it.name.contains("Run ") || it.strategyLabel.contains("#") })
    }

    // endregion

    // region Summary

    @Test
    fun inProgressSummary_showsMeasuredMetricsFromTheAnalyticsSummary() {
        val view = ready(inProgress())

        assertEquals("모멘텀 운영", view.header.name)
        assertEquals("기본 모멘텀 전략 · V2", view.header.strategyLabel)
        assertEquals("운영 중", view.header.badge.label)
        assertEquals("진행 중", view.header.statusLabel)
        assertEquals("2026.09.18 ~ 2026.09.30", view.header.period)
        assertEquals("9일", view.header.tradingDays)
        val metrics = view.metrics as KeyMetricsView.Measured
        assertEquals("100,331,524원", metrics.totalAsset)
    }

    @Test
    fun completeSummary_isMarkedAsAggregated() {
        val view = ready(PerformanceFixtures.runData(1, RunStatus.COMPLETED, PerformanceStatus.COMPLETE))
        assertEquals("집계 완료", view.header.statusLabel)
        assertEquals("완료", view.header.badge.label)
        assertTrue(view.metrics is KeyMetricsView.Measured)
    }

    @Test
    fun cumulativeProfit_isSignedWon() {
        val data = inProgress()
        assertEquals("+331,524원", (ready(data).metrics as KeyMetricsView.Measured).cumulativeProfit)
        val loss = data.copy(summary = data.summary.copy(cumulativeProfit = -120_000))
        assertEquals("-120,000원", (ready(loss).metrics as KeyMetricsView.Measured).cumulativeProfit)
        val flat = data.copy(summary = data.summary.copy(cumulativeProfit = 0))
        assertEquals("0원", (ready(flat).metrics as KeyMetricsView.Measured).cumulativeProfit)
    }

    @Test
    fun cumulativeReturn_isSignedPercent() {
        val data = inProgress()
        assertEquals("+0.33%", (ready(data).metrics as KeyMetricsView.Measured).cumulativeReturn)
        val loss = data.copy(summary = data.summary.copy(cumulativeReturn = BigDecimal("-0.0842")))
        assertEquals("-8.42%", (ready(loss).metrics as KeyMetricsView.Measured).cumulativeReturn)
    }

    @Test
    fun mdd_isTheAnalyticsValue_notRecomputedFromTheSeries() {
        val data = inProgress()
        // The series' own worst drawdown is -1%; the summary's -8.42% is what must be shown.
        val metrics = ready(data).metrics as KeyMetricsView.Measured
        assertEquals("-8.42%", metrics.maxDrawdown)

        val noMdd = data.copy(summary = data.summary.copy(maxDrawdown = null))
        assertEquals("—", (ready(noMdd).metrics as KeyMetricsView.Measured).maxDrawdown)
    }

    @Test
    fun cagrNull_isUnavailable_notZero() {
        val metrics = ready(inProgress()).metrics as KeyMetricsView.Measured
        assertEquals("계산 불가", metrics.cagr)
        assertEquals(PerformancePresenter.CAGR_NOTE, metrics.cagrNote)

        val data = inProgress()
        val withCagr = data.copy(summary = data.summary.copy(cagr = BigDecimal("0.123456")))
        val measured = ready(withCagr).metrics as KeyMetricsView.Measured
        assertEquals("+12.35%", measured.cagr)
        assertNull(measured.cagrNote)
    }

    @Test
    fun empty_showsInitialCapital_neverFakeZeroPerformance() {
        val data = PerformanceFixtures.runData(4, RunStatus.READY, PerformanceStatus.EMPTY)
        val view = ready(data, PerformanceFixtures.detail(data, emptyList(), emptyList()))

        val metrics = view.metrics as KeyMetricsView.NotValued
        assertEquals("아직 일별 평가 기록이 없습니다.", metrics.message)
        assertEquals("100,000,000원", metrics.initialCash)
        assertEquals("평가 전", view.header.tradingDays)
        assertNull(view.equity)
        assertTrue(view.monthly.isEmpty())
        val visible = view.metrics.toString() + view.equity + view.daily
        listOf("0원,", "0.00%", "MDD 0").forEach { assertFalse(it, visible.contains(it)) }
        // Trade counts that genuinely exist are still shown.
        assertEquals("4건", stat(view.trades.rows, "완료 거래"))
    }

    @Test
    fun dataError_isAFixedSafeWarning_withoutRawErrorOrMetrics() {
        val data = PerformanceFixtures.runData(7, RunStatus.RUNNING, PerformanceStatus.DATA_ERROR)

        val view = PerformancePresenter.detail(PerformanceFixtures.detail(data, emptyList(), emptyList()))

        view as PerformanceDetailView.DataError
        assertEquals("성과 데이터를 계산할 수 없습니다.", view.title)
        assertEquals("저장된 모의투자 기록의 정합성을 확인해야 합니다.", view.guidance)
        assertEquals("데이터 확인 필요", view.header.statusLabel)
        val visible = view.toString()
        listOf(PerformanceFixtures.RAW_ERROR, "SQLite", "mismatch", "run_id").forEach { assertFalse(it, visible.contains(it)) }
        assertEquals(
            "데이터 확인 필요",
            PerformancePresenter.runRows(listOf(data)).single().assetSummary,
        )
    }

    // endregion

    // region Equity curve, daily rows and monthly returns

    @Test
    fun equitySeries_isChronological_withStartAndLatestVisible() {
        val shuffled = PerformanceFixtures.SERIES.reversed()

        val equity = PerformancePresenter.equity(shuffled)!!

        assertEquals(PerformanceFixtures.SERIES.map { it.date }, equity.points.map { it.date })
        assertEquals("2026.09.18", equity.startDate)
        assertEquals("2026.09.30", equity.endDate)
        assertEquals("100,000,000원", equity.firstAsset)
        assertEquals("100,331,524원", equity.latestAsset)
        assertEquals("100,331,524원", equity.highAsset)
        assertEquals("99,000,000원", equity.lowAsset)
        assertTrue(equity.description.contains("2026.09.30 100,331,524원"))
    }

    @Test
    fun equityChart_usesTheActualPointsOnly_noInterpolatedTradingDays() {
        val equity = PerformancePresenter.equity(PerformanceFixtures.SERIES)!!

        // 09.18, 09.21, 09.30: the gap days are not filled in.
        assertEquals(3, equity.points.size)
        assertEquals(3, equity.fractions.size)
        assertEquals(PerformanceFixtures.SERIES.map { it.totalAsset }, equity.points.map { it.totalAsset })
        assertNull(PerformancePresenter.equity(emptyList()))
    }

    @Test
    fun chartFractions_normalizeOnLongValues_withoutPrecisionLossOrOverflow() {
        val large = listOf(9_000_000_000_000_001L, 9_000_000_000_000_003L, 9_000_000_000_000_002L)
        assertEquals(listOf(0f, 1f, 0.5f), PerformancePresenter.chartFractions(large))
        assertEquals(listOf(0.5f, 0.5f), PerformancePresenter.chartFractions(listOf(100L, 100L)))
        assertEquals(listOf(0.5f), PerformancePresenter.chartFractions(listOf(0L)))
        assertTrue(PerformancePresenter.chartFractions(emptyList()).isEmpty())
    }

    @Test
    fun dailyRows_areNewestFirst_andLimited() {
        val series = (0 until 40).map { point(LocalDate.of(2026, 9, 1).plusDays(it.toLong()), 100_000_000L + it) }

        val rows = PerformancePresenter.daily(series)

        assertEquals(PerformancePresenter.DAILY_ROWS, rows.size)
        assertEquals("2026.10.10", rows.first().date)
        val latest = PerformancePresenter.daily(PerformanceFixtures.SERIES)
        assertEquals("2026.09.30", latest.first().date)
        assertEquals("+1,331,524원", latest.first().dailyProfit)
        assertEquals("-1,000,000원", latest[1].dailyProfit)
        assertEquals("-1.00%", latest[1].drawdown)
    }

    @Test
    fun monthlyReturns_haveTextualSignedDirection() {
        val rows = ready(inProgress()).monthly

        assertEquals(listOf("2026년 9월", "2026년 10월"), rows.map { it.label })
        assertEquals(listOf("+3.21%", "-1.08%"), rows.map { it.returnRate })
        assertEquals(listOf(ReturnDirection.UP, ReturnDirection.DOWN), rows.map { it.direction })
        assertEquals("103,210,000원", rows.first().endAsset)
    }

    // endregion

    // region Trades and signals

    @Test
    fun closedTradeCount_comesFromTheSummary() {
        assertEquals("4건", stat(ready(inProgress()).trades.rows, "완료 거래"))
    }

    @Test
    fun winLossBreakevenCounts_comeFromTheSummary() {
        val rows = ready(inProgress()).trades.rows
        assertEquals("2건", stat(rows, "수익 거래"))
        assertEquals("1건", stat(rows, "손실 거래"))
        assertEquals("1건", stat(rows, "손익 없음"))
        assertEquals("1건", stat(rows, "미종결 거래"))
        assertEquals("66.67%", stat(rows, "승률"))
        assertNull(ready(inProgress()).trades.winRateNote)
    }

    @Test
    fun winRateNull_isADash_withAnExplanation_notZeroPercent() {
        val data = inProgress()
        val noDecisive = data.copy(summary = data.summary.copy(winRate = null, winningTrades = 0, losingTrades = 0))

        val trades = ready(noDecisive).trades

        assertEquals("—", stat(trades.rows, "승률"))
        assertEquals("수익/손실로 종료된 거래가 아직 없습니다.", trades.winRateNote)
        val zero = data.copy(summary = data.summary.copy(winRate = BigDecimal.ZERO))
        assertEquals("0.00%", stat(ready(zero).trades.rows, "승률"))
    }

    @Test
    fun averageBestWorstReturns_areSignedPercents() {
        val rows = ready(inProgress()).trades.rows
        assertEquals("+1.23%", stat(rows, "평균 거래 수익률"))
        assertEquals("+5.10%", stat(rows, "최고 거래 수익률"))
        assertEquals("-2.00%", stat(rows, "최저 거래 수익률"))
        val empty = PerformanceFixtures.runData(4, RunStatus.READY, PerformanceStatus.EMPTY)
        val emptyRows = ready(empty, PerformanceFixtures.detail(empty, emptyList(), emptyList())).trades.rows
        listOf("평균 거래 수익률", "최고 거래 수익률", "최저 거래 수익률", "평균 보유일").forEach { assertEquals("—", stat(emptyRows, it)) }
    }

    @Test
    fun averageHoldingDays_isShownWithOneDecimal() {
        assertEquals("3.5일", stat(ready(inProgress()).trades.rows, "평균 보유일"))
        assertEquals("2.0일", PerformancePresenter.days(BigDecimal("2.000000000000")))
    }

    @Test
    fun signalCounts_areStrategyDecisions_notOrders() {
        val signals = ready(inProgress()).signals
        assertEquals(
            listOf(StatRow("매수 판단", "5건"), StatRow("매도 판단", "4건"), StatRow("관망 판단", "12건"), StatRow("조치 없음", "3건")),
            signals.decisions,
        )
        assertTrue(signals.note.contains("주문이 아닙니다"))
        assertFalse(signals.decisions.any { it.label.contains("주문") })
    }

    @Test
    fun executionCounts_areVirtualFills() {
        val signals = ready(inProgress()).signals
        assertEquals(listOf(StatRow("매수 체결", "5건"), StatRow("매도 체결", "4건")), signals.executions)
        assertEquals("현재 보유 종목 1개 · 미종결 거래 1건", ready(inProgress()).positionLine)
    }

    // endregion

    // region Comparison

    @Test
    fun comparison_showsFactsAndPolicyContext_perRun() {
        val runRows = PerformancePresenter.runRows(FakePerformanceDataSource().runs).associateBy { it.runId }
        val view = PerformancePresenter.comparison(
            listOf(
                PerformanceFixtures.comparisonRow(1, "지난 운영", PerformanceStatus.COMPLETE),
                PerformanceFixtures.comparisonRow(3, "모멘텀 운영"),
            ),
            runRows,
        )

        assertEquals(PerformancePresenter.COMPARE_NOTE, view.note)
        val completed = view.columns.first()
        assertEquals("지난 운영", completed.name)
        assertEquals("기본 모멘텀 전략 · V2", completed.strategyLabel)
        assertEquals("완료", completed.badge!!.label)
        assertEquals("2026.09.18 ~ 2026.09.30", stat(completed.metrics, "기간"))
        assertEquals("9일", stat(completed.metrics, "거래일 수"))
        assertEquals("100,000,000원", stat(completed.metrics, "초기자금"))
        assertEquals("100,331,524원", stat(completed.metrics, "최종 자산"))
        assertEquals("+0.33%", stat(completed.metrics, "누적 수익률"))
        assertEquals("-8.42%", stat(completed.metrics, "최대 낙폭(MDD)"))
        assertEquals("4건", stat(completed.metrics, "완료 거래"))
        assertEquals("50.00%", stat(completed.metrics, "승률"))
        assertEquals("100,331,524원", stat(view.columns[1].metrics, "최근 자산"))

        assertEquals("10%", stat(completed.policy, "1회 매수 비중"))
        assertEquals("0.015%", stat(completed.policy, "수수료 가정"))
        assertEquals("0.2%", stat(completed.policy, "매도세 가정"))
        assertEquals("PAPER_POLICY_V1", stat(completed.policy, "정책 버전"))
    }

    @Test
    fun comparison_showsMissingPolicyAndDataError_asFacts() {
        val view = PerformancePresenter.comparison(
            listOf(
                PerformanceFixtures.comparisonRow(2, "go hbm", PerformanceStatus.EMPTY, withPolicy = false),
                PerformanceFixtures.comparisonRow(7, "검증 실패", PerformanceStatus.DATA_ERROR),
            ),
            emptyMap(),
        )

        val draft = view.columns[0]
        assertEquals("평가 전", stat(draft.metrics, "최근 자산"))
        assertEquals("—", stat(draft.metrics, "누적 수익률"))
        assertEquals("운영 준비 전이라 아직 없음", stat(draft.policy, "거래 정책"))
        val broken = view.columns[1]
        assertEquals("성과 데이터를 계산할 수 없습니다.", broken.warning)
        assertEquals(listOf("기간", "초기자금"), broken.metrics.map { it.label })
        assertNull(broken.badge)
    }

    @Test
    fun comparison_hasNoWinnerRankingOrScore() {
        val view = PerformancePresenter.comparison(
            listOf(PerformanceFixtures.comparisonRow(1, "A"), PerformanceFixtures.comparisonRow(3, "B")),
            emptyMap(),
        )

        val visible = view.toString()
        listOf("1등", "최고 전략", "우승", "추천", "Best", "winner", "Winner", "순위", "점수", "rank", "score")
            .forEach { assertFalse(it, visible.contains(it)) }
        val fields = (ComparisonView::class.java.declaredFields + ComparisonColumn::class.java.declaredFields).map { it.name.lowercase() }
        listOf("rank", "score", "winner", "best").forEach { banned -> assertFalse(banned, fields.any { it.contains(banned) }) }
        assertEquals(listOf("A", "B"), view.columns.map { it.name })
    }

    // endregion

    @Test
    fun formatting_matchesTheProductConventions() {
        assertEquals("100,331,524원", PerformancePresenter.won(100_331_524))
        assertEquals("+331,524원", PerformancePresenter.signedWon(331_524))
        assertEquals("-120,000원", PerformancePresenter.signedWon(-120_000))
        assertEquals("+0.33%", PerformancePresenter.signedPercent(BigDecimal("0.00331524")))
        assertEquals("-8.42%", PerformancePresenter.signedPercent(BigDecimal("-0.0842")))
        assertEquals("2026.09.29", PerformancePresenter.dotDate(LocalDate.of(2026, 9, 29)))
        assertEquals("2026년 9월", PerformancePresenter.monthLabel(2026, 9))
        assertEquals("3.5일", PerformancePresenter.days(BigDecimal("3.5")))
    }
}
