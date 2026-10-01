package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.core.analytics.DailyPerformancePoint
import com.mirunubi.bjstock.core.analytics.MonthlyPerformance
import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.RunComparisonRow
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// region UI state

data class PerformanceUiState(
    val runs: PerformanceRunsState = PerformanceRunsState.Loading,
    val selectedRunId: Long? = null,
    val detail: PerformanceDetailState = PerformanceDetailState.None,
    val comparison: ComparisonState = ComparisonState(),
)

sealed interface PerformanceRunsState {
    data object Loading : PerformanceRunsState

    data class Failed(val message: String) : PerformanceRunsState

    data class Loaded(val rows: List<PerformanceRunRow>) : PerformanceRunsState
}

data class PerformanceRunBadge(val status: RunStatus, val label: String)

data class PerformanceRunRow(
    val runId: Long,
    val name: String,
    val strategyLabel: String,
    val badge: PerformanceRunBadge,
    val period: String,
    val assetSummary: String,
)

sealed interface PerformanceDetailState {
    data object None : PerformanceDetailState

    data object Loading : PerformanceDetailState

    data class Failed(val message: String) : PerformanceDetailState

    data class Loaded(val view: PerformanceDetailView) : PerformanceDetailState
}

data class StatRow(val label: String, val value: String)

data class PerformanceHeader(
    val name: String,
    val strategyLabel: String,
    val badge: PerformanceRunBadge,
    val statusLabel: String,
    val period: String,
    val tradingDays: String,
)

sealed interface PerformanceDetailView {
    val header: PerformanceHeader

    /** Analytics reported DATA_ERROR; no metric is shown and the raw error text never reaches the UI. */
    data class DataError(override val header: PerformanceHeader, val title: String, val guidance: String) :
        PerformanceDetailView

    data class Ready(
        override val header: PerformanceHeader,
        val metrics: KeyMetricsView,
        val equity: EquityView?,
        val daily: List<DailyRowView>,
        val monthly: List<MonthlyRowView>,
        val trades: TradeStatsView,
        val signals: SignalStatsView,
        val positionLine: String,
    ) : PerformanceDetailView
}

sealed interface KeyMetricsView {
    data class Measured(
        val totalAsset: String,
        val cumulativeProfit: String,
        val cumulativeReturn: String,
        val maxDrawdown: String,
        val cagr: String,
        val cagrNote: String?,
    ) : KeyMetricsView

    /** Before the first daily snapshot: the initial capital, never a measured 0원 / 0.00% / MDD 0%. */
    data class NotValued(val message: String, val initialCash: String) : KeyMetricsView
}

data class EquityPoint(val date: LocalDate, val totalAsset: Long)

data class EquityView(
    /** Actual snapshot dates only, oldest first; missing trading days are not interpolated. */
    val points: List<EquityPoint>,
    /** Vertical position of each point in 0..1 (0 = lowest asset), aligned with [points]. */
    val fractions: List<Float>,
    val startDate: String,
    val endDate: String,
    val firstAsset: String,
    val latestAsset: String,
    val highAsset: String,
    val lowAsset: String,
    val description: String,
)

data class DailyRowView(
    val date: String,
    val totalAsset: String,
    val dailyProfit: String,
    val dailyReturn: String,
    val cumulativeReturn: String,
    val drawdown: String,
)

enum class ReturnDirection { UP, DOWN, FLAT }

data class MonthlyRowView(val label: String, val returnRate: String, val direction: ReturnDirection, val endAsset: String)

data class TradeStatsView(val rows: List<StatRow>, val winRateNote: String?, val note: String)

data class SignalStatsView(val decisions: List<StatRow>, val executions: List<StatRow>, val note: String)

data class ComparisonState(
    val open: Boolean = false,
    /** Run ids in the order the user picked them. */
    val selected: List<Long> = emptyList(),
    val message: String? = null,
    val result: ComparisonResult = ComparisonResult.None,
)

sealed interface ComparisonResult {
    data object None : ComparisonResult

    data object Loading : ComparisonResult

    data class Failed(val message: String) : ComparisonResult

    data class Loaded(val view: ComparisonView) : ComparisonResult
}

/** Facts per Run side by side. There is deliberately no score, rank or winner field. */
data class ComparisonView(val columns: List<ComparisonColumn>, val note: String)

data class ComparisonColumn(
    val name: String,
    val strategyLabel: String,
    val badge: PerformanceRunBadge?,
    val statusLabel: String,
    val warning: String?,
    val metrics: List<StatRow>,
    val policy: List<StatRow>,
)

// endregion

/** Pure mapping from analytics results to Korean presentation. Every number comes from the analytics layer. */
object PerformancePresenter {
    const val RUNS_EMPTY = "아직 모의투자가 없습니다. 모의투자 탭에서 시작해 주세요."
    const val RUNS_FAILED = "성과 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val DETAIL_FAILED = "성과 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val COMPARE_FAILED = "비교 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val DATA_ERROR_TITLE = "성과 데이터를 계산할 수 없습니다."
    const val DATA_ERROR_GUIDANCE = "저장된 모의투자 기록의 정합성을 확인해야 합니다."
    const val NOT_VALUED_MESSAGE = "아직 일별 평가 기록이 없습니다."
    const val EQUITY_EMPTY = "아직 일별 평가 기록이 없어 자산 추이를 표시할 수 없습니다."
    const val MONTHLY_EMPTY = "월간 성과 데이터가 아직 없습니다."
    const val WIN_RATE_NONE = "수익/손실로 종료된 거래가 아직 없습니다."
    const val TRADE_NOTE = "거래 수익률은 수수료·세금을 반영한 가상 체결 기준입니다."
    const val SIGNAL_NOTE = "전략 판단은 주문이 아닙니다. 실제 주식 주문은 발생하지 않습니다."
    const val CAGR_NOTE = "운영 기간이 1년 미만이면 연환산 수익률(CAGR)을 계산하지 않습니다."
    const val CAGR_UNAVAILABLE = "계산 불가"
    const val NONE = "—"
    const val COMPARE_NOTE = "기간과 거래 정책이 다른 모의투자는 단순 비교에 주의하세요."
    const val COMPARE_MIN_MESSAGE = "비교할 모의투자를 2개 이상 선택해 주세요."
    const val COMPARE_MAX_MESSAGE = "최대 3개까지 선택할 수 있습니다."
    const val POSITIONS_NOTE = "보유 종목 상세는 모의투자 탭에서 확인할 수 있습니다."
    const val MIN_COMPARE = 2
    const val MAX_COMPARE = 3
    const val DAILY_ROWS = 15

    private val RUN_RANK = listOf(
        RunStatus.RUNNING,
        RunStatus.READY,
        RunStatus.PAUSED,
        RunStatus.DRAFT,
        RunStatus.COMPLETED,
        RunStatus.CANCELLED,
    )

    // region Run selector

    fun badge(status: RunStatus) = PerformanceRunBadge(status, KoreanLabels.runStatus(status))

    fun performanceStatus(status: PerformanceStatus): String = when (status) {
        PerformanceStatus.EMPTY -> "평가 전"
        PerformanceStatus.IN_PROGRESS -> "진행 중"
        PerformanceStatus.COMPLETE -> "집계 완료"
        PerformanceStatus.DATA_ERROR -> "데이터 확인 필요"
    }

    /** Same order as 모의투자: RUNNING, READY, PAUSED, DRAFT, COMPLETED, CANCELLED, then newest first. */
    fun sortRuns(runs: List<PerformanceRunData>): List<PerformanceRunData> =
        runs.sortedWith(compareBy<PerformanceRunData> { RUN_RANK.indexOf(it.run.status) }.thenByDescending { it.run.id })

    fun runRows(runs: List<PerformanceRunData>): List<PerformanceRunRow> = sortRuns(runs).map { data ->
        PerformanceRunRow(
            runId = data.run.id,
            name = data.run.runName,
            strategyLabel = strategyLabel(data),
            badge = badge(data.run.status),
            period = period(data.run.startDate, data.summary.endDate),
            assetSummary = assetSummary(data.summary),
        )
    }

    fun defaultSelection(runs: List<PerformanceRunData>): Long? = sortRuns(runs).firstOrNull()?.run?.id

    private fun assetSummary(summary: RunPerformanceSummary): String {
        if (summary.status == PerformanceStatus.DATA_ERROR) return performanceStatus(PerformanceStatus.DATA_ERROR)
        val total = summary.latestTotalAsset ?: return "초기자금 ${won(summary.initialCash)} · 평가 전"
        return "총 모의자산 ${won(total)}" + (summary.cumulativeReturn?.let { " · ${signedPercent(it)}" } ?: "")
    }

    private fun strategyLabel(data: PerformanceRunData) = "${data.strategyName} · ${data.versionLabel}"

    // endregion

    // region Detail

    fun header(data: PerformanceRunData) = PerformanceHeader(
        name = data.run.runName,
        strategyLabel = strategyLabel(data),
        badge = badge(data.run.status),
        statusLabel = performanceStatus(data.summary.status),
        period = period(data.run.startDate, data.summary.endDate),
        tradingDays = data.summary.tradingDays.takeIf { it > 0 }?.let { "${it}일" } ?: "평가 전",
    )

    fun detail(data: PerformanceDetailData): PerformanceDetailView {
        val summary = data.run.summary
        val header = header(data.run)
        if (summary.status == PerformanceStatus.DATA_ERROR) {
            return PerformanceDetailView.DataError(header, DATA_ERROR_TITLE, DATA_ERROR_GUIDANCE)
        }
        return PerformanceDetailView.Ready(
            header = header,
            metrics = metrics(summary),
            equity = equity(data.series),
            daily = daily(data.series),
            monthly = data.monthly.map(::monthly),
            trades = trades(summary),
            signals = signals(summary),
            positionLine = "현재 보유 종목 ${summary.openPositions}개 · 미종결 거래 ${summary.openTrades}건",
        )
    }

    /** CAGR and MDD are exactly what the analytics summary returned; nothing is recomputed here. */
    fun metrics(summary: RunPerformanceSummary): KeyMetricsView {
        val total = summary.latestTotalAsset
        if (summary.status == PerformanceStatus.EMPTY || total == null) {
            return KeyMetricsView.NotValued(NOT_VALUED_MESSAGE, won(summary.initialCash))
        }
        return KeyMetricsView.Measured(
            totalAsset = won(total),
            cumulativeProfit = summary.cumulativeProfit?.let(::signedWon) ?: NONE,
            cumulativeReturn = summary.cumulativeReturn?.let(::signedPercent) ?: NONE,
            maxDrawdown = summary.maxDrawdown?.let(::signedPercent) ?: NONE,
            cagr = summary.cagr?.let(::signedPercent) ?: CAGR_UNAVAILABLE,
            cagrNote = if (summary.cagr == null) CAGR_NOTE else null,
        )
    }

    fun equity(series: List<DailyPerformancePoint>): EquityView? {
        if (series.isEmpty()) return null
        val ordered = series.sortedBy { it.date }
        val first = ordered.first()
        val latest = ordered.last()
        val assets = ordered.map { it.totalAsset }
        return EquityView(
            points = ordered.map { EquityPoint(it.date, it.totalAsset) },
            fractions = chartFractions(assets),
            startDate = dotDate(first.date),
            endDate = dotDate(latest.date),
            firstAsset = won(first.totalAsset),
            latestAsset = won(latest.totalAsset),
            highAsset = won(assets.max()),
            lowAsset = won(assets.min()),
            description = "자산 추이 그래프: ${dotDate(first.date)} ${won(first.totalAsset)}에서 " +
                "${dotDate(latest.date)} ${won(latest.totalAsset)}까지, 평가일 ${ordered.size}일",
        )
    }

    /**
     * Normalizes Long assets to 0..1 for drawing. The range is taken on the Long values (assets are validated
     * non-negative, so `max - min` cannot overflow); only the final ratio becomes a Float.
     */
    fun chartFractions(values: List<Long>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val min = values.min()
        val range = values.max() - min
        if (range == 0L) return values.map { 0.5f }
        return values.map { ((it - min).toDouble() / range.toDouble()).toFloat() }
    }

    /** Newest first, limited to the latest [DAILY_ROWS] snapshots. */
    fun daily(series: List<DailyPerformancePoint>): List<DailyRowView> =
        series.sortedBy { it.date }.takeLast(DAILY_ROWS).reversed().map { point ->
            DailyRowView(
                date = dotDate(point.date),
                totalAsset = won(point.totalAsset),
                dailyProfit = signedWon(point.dailyProfit),
                dailyReturn = signedPercent(point.dailyReturn),
                cumulativeReturn = signedPercent(point.cumulativeReturn),
                drawdown = signedPercent(point.drawdown),
            )
        }

    fun monthly(month: MonthlyPerformance) = MonthlyRowView(
        label = monthLabel(month.year, month.month),
        returnRate = signedPercent(month.returnRate),
        direction = direction(month.returnRate),
        endAsset = won(month.endAsset),
    )

    fun trades(summary: RunPerformanceSummary) = TradeStatsView(
        rows = listOf(
            StatRow("완료 거래", count(summary.closedTrades)),
            StatRow("수익 거래", count(summary.winningTrades)),
            StatRow("손실 거래", count(summary.losingTrades)),
            StatRow("손익 없음", count(summary.breakevenTrades)),
            StatRow("승률", summary.winRate?.let(::plainPercent) ?: NONE),
            StatRow("평균 거래 수익률", summary.averageTradeReturn?.let(::signedPercent) ?: NONE),
            StatRow("최고 거래 수익률", summary.bestTradeReturn?.let(::signedPercent) ?: NONE),
            StatRow("최저 거래 수익률", summary.worstTradeReturn?.let(::signedPercent) ?: NONE),
            StatRow("평균 보유일", summary.averageHoldingDays?.let(::days) ?: NONE),
            StatRow("미종결 거래", count(summary.openTrades)),
        ),
        winRateNote = if (summary.winRate == null) WIN_RATE_NONE else null,
        note = TRADE_NOTE,
    )

    fun signals(summary: RunPerformanceSummary) = SignalStatsView(
        decisions = listOf(
            StatRow("매수 판단", count(summary.buySignals)),
            StatRow("매도 판단", count(summary.sellSignals)),
            StatRow("관망 판단", count(summary.holdSignals)),
            StatRow("조치 없음", count(summary.noActionSignals)),
        ),
        executions = listOf(
            StatRow("매수 체결", count(summary.buyExecutions)),
            StatRow("매도 체결", count(summary.sellExecutions)),
        ),
        note = SIGNAL_NOTE,
    )

    // endregion

    // region Comparison

    /** Columns keep the service order; nothing is ranked, scored or normalized. */
    fun comparison(rows: List<RunComparisonRow>, runRows: Map<Long, PerformanceRunRow>) = ComparisonView(
        columns = rows.map { row -> column(row, runRows[row.runId]) },
        note = COMPARE_NOTE,
    )

    private fun column(row: RunComparisonRow, runRow: PerformanceRunRow?): ComparisonColumn {
        val dataError = row.performanceStatus == PerformanceStatus.DATA_ERROR
        val period = StatRow("기간", period(row.startDate, row.endDate))
        val initial = StatRow("초기자금", won(row.initialCash))
        val metrics = if (dataError) {
            listOf(period, initial)
        } else {
            listOf(
                period,
                StatRow("거래일 수", "${row.tradingDays}일"),
                initial,
                StatRow(
                    if (row.performanceStatus == PerformanceStatus.COMPLETE) "최종 자산" else "최근 자산",
                    row.latestAsset?.let(::won) ?: "평가 전",
                ),
                StatRow("누적 수익률", row.cumulativeReturn?.let(::signedPercent) ?: NONE),
                StatRow("최대 낙폭(MDD)", row.maxDrawdown?.let(::signedPercent) ?: NONE),
                StatRow("완료 거래", count(row.closedTrades)),
                StatRow("승률", row.winRate?.let(::plainPercent) ?: NONE),
            )
        }
        val policy = if (row.policyVersion == null) {
            listOf(StatRow("거래 정책", "운영 준비 전이라 아직 없음"))
        } else {
            listOf(
                StatRow("정책 버전", row.policyVersion),
                StatRow("1회 매수 비중", row.buyAllocationRate?.let(::rate) ?: NONE),
                StatRow("수수료 가정", row.commissionRate?.let(::rate) ?: NONE),
                StatRow("매도세 가정", row.sellTaxRate?.let(::rate) ?: NONE),
            )
        }
        return ComparisonColumn(
            name = row.runName,
            strategyLabel = "${row.strategyName} · ${row.strategyVersion}",
            badge = runRow?.badge,
            statusLabel = performanceStatus(row.performanceStatus),
            warning = if (dataError) DATA_ERROR_TITLE else null,
            metrics = metrics,
            policy = policy,
        )
    }

    // endregion

    // region Formatting

    /** e.g. `100,331,524원`. */
    fun won(value: Long): String = "%,d원".format(value)

    /** e.g. `+331,524원`, `-120,000원`, `0원`. */
    fun signedWon(value: Long): String = if (value > 0L) "+${won(value)}" else won(value)

    /** e.g. `+0.33%`, `-8.42%`. */
    fun signedPercent(rate: BigDecimal): String = PerformanceMath.formatSignedPercent(rate)

    /** Unsigned ratio such as a win rate, e.g. `50.00%`. */
    fun plainPercent(rate: BigDecimal): String =
        rate.multiply(BigDecimal(100)).setScale(PerformanceMath.DISPLAY_PERCENT_SCALE, RoundingMode.HALF_UP).toPlainString() + "%"

    /** Stored policy rate as written, e.g. `0.015%`, `10%`. */
    fun rate(rate: BigDecimal): String =
        rate.multiply(BigDecimal(100)).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString() + "%"

    /** e.g. `3.5일`. */
    fun days(value: BigDecimal): String = value.setScale(1, RoundingMode.HALF_UP).toPlainString() + "일"

    fun count(value: Int): String = "${value}건"

    /** e.g. `2026.09.29`. */
    fun dotDate(date: LocalDate): String = "%04d.%02d.%02d".format(date.year, date.monthValue, date.dayOfMonth)

    /** e.g. `2026년 9월`. */
    fun monthLabel(year: Int, month: Int): String = "${year}년 ${month}월"

    fun period(start: LocalDate?, end: LocalDate?): String = when {
        start == null -> NONE
        end == null -> "${dotDate(start)}부터"
        else -> "${dotDate(start)} ~ ${dotDate(end)}"
    }

    fun direction(rate: BigDecimal): ReturnDirection = when (rate.signum()) {
        1 -> ReturnDirection.UP
        -1 -> ReturnDirection.DOWN
        else -> ReturnDirection.FLAT
    }

    // endregion
}
