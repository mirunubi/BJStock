package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.OpenPositionView
import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.math.RoundingMode

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Error(val message: String) : HomeUiState

    data class Content(
        val portfolio: PortfolioCard,
        val decision: DecisionCard,
        val holdings: HoldingsCard,
        val auto: AutoCard,
        val alerts: List<HomeAlert>,
    ) : HomeUiState
}

sealed interface PortfolioCard {
    data object NoRun : PortfolioCard

    data class Summary(
        val runLabel: String,
        val runStatus: String,
        val totalAssetLabel: String,
        val totalAsset: String,
        val cumulativeReturn: String,
        val cumulativeProfit: String?,
        val otherRunsNote: String?,
    ) : PortfolioCard
}

sealed interface DecisionCard {
    data class Empty(val message: String) : DecisionCard

    data class Latest(
        val instrument: String,
        val decision: String,
        val canonicalDecision: String,
        val score: String,
        val date: String,
        val moreNote: String?,
    ) : DecisionCard
}

sealed interface HoldingsCard {
    data class Empty(val message: String) : HoldingsCard

    data class Holdings(val count: Int, val items: List<HoldingRow>, val moreNote: String?) : HoldingsCard
}

data class HoldingRow(
    val instrument: String,
    val quantity: String,
    val averagePrice: String,
    val unrealizedPnl: String,
)

data class AutoCard(
    val enabled: Boolean,
    val stateLabel: String,
    val nextRun: String,
    val latestOperation: String,
)

enum class AlertLevel { WARNING, ERROR }

data class HomeAlert(val level: AlertLevel, val message: String)

object HomePresenter {
    const val NO_RUN = "실행 중인 모의투자가 없습니다"
    const val NO_DECISION = "최근 전략 판단 없음"
    const val NO_HOLDINGS = "보유종목 없음"
    const val NO_OPERATION = "실행 기록 없음"
    const val AUTO_OFF_NEXT = "자동운영이 꺼져 있습니다"
    const val AUTO_NO_SLOT = "예약 정보 없음"
    const val LOAD_FAILED = "홈 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."

    private const val MAX_HOLDING_ROWS = 3
    private val FINANCIAL_INTEGRITY_CODES =
        setOf("LEDGER_MISMATCH", "EXECUTION_IDEMPOTENCY_CONFLICT", "FILLED_ORDER_WITHOUT_EXECUTION")

    fun present(snapshot: HomeSnapshot): HomeUiState.Content {
        val runData = snapshot.run
        return HomeUiState.Content(
            portfolio = runData?.let { portfolio(it, snapshot.candidateRunCount) } ?: PortfolioCard.NoRun,
            decision = runData?.let(::decision) ?: DecisionCard.Empty(NO_DECISION),
            holdings = runData?.let(::holdings) ?: HoldingsCard.Empty(NO_RUN),
            auto = auto(snapshot),
            alerts = alerts(snapshot),
        )
    }

    private fun portfolio(data: HomeRunData, candidateCount: Int): PortfolioCard.Summary {
        val summary = data.summary
        val valued = summary.latestTotalAsset
        return PortfolioCard.Summary(
            runLabel = "${data.run.runName} · ${summary.strategyName} ${summary.strategyVersionLabel}",
            runStatus = KoreanLabels.runStatus(data.run.status),
            totalAssetLabel = if (valued != null) "총 모의자산" else "초기 자본 (아직 평가 전)",
            totalAsset = PerformanceMath.formatWon(valued ?: data.run.initialCash),
            cumulativeReturn = summary.cumulativeReturn?.let(PerformanceMath::formatSignedPercent) ?: "—",
            cumulativeProfit = summary.cumulativeProfit?.let(PerformanceMath::formatSignedWon),
            otherRunsNote = (candidateCount - 1).takeIf { it > 0 }?.let { "다른 모의투자 ${it}개는 모의투자 탭에서 확인" },
        )
    }

    private fun decision(data: HomeRunData): DecisionCard {
        val evaluation = data.latestEvaluation ?: return DecisionCard.Empty(NO_DECISION)
        val instrument = data.latestEvaluationInstrument
        val score = StrategyScoreMath.scoreToDisplay(evaluation.finalScore).setScale(2, RoundingMode.HALF_UP)
        return DecisionCard.Latest(
            instrument = instrument?.let { "${it.name} ${it.symbol}" } ?: "종목 정보 없음",
            decision = KoreanLabels.decision(evaluation.finalDecision),
            canonicalDecision = evaluation.finalDecision.name,
            score = score.toPlainString(),
            date = KoreanLabels.date(evaluation.evaluationDate),
            moreNote = (data.sameDayEvaluationCount - 1).takeIf { it > 0 }?.let { "같은 날 다른 종목 판단 ${it}건" },
        )
    }

    private fun holdings(data: HomeRunData): HoldingsCard {
        if (data.positions.isEmpty()) return HoldingsCard.Empty(NO_HOLDINGS)
        val ordered = data.positions.sortedWith(
            compareByDescending<OpenPositionView> { it.marketValue ?: Long.MIN_VALUE }
                .thenBy { it.symbol },
        )
        return HoldingsCard.Holdings(
            count = data.positions.size,
            items = ordered.take(MAX_HOLDING_ROWS).map { position ->
                HoldingRow(
                    instrument = "${data.instrumentNames[position.instrumentId] ?: position.symbol} ${position.symbol}",
                    quantity = "${"%,d".format(position.quantity)}주",
                    averagePrice = PerformanceMath.formatWon(position.averagePrice),
                    unrealizedPnl = position.unrealizedPricePnl?.let(PerformanceMath::formatSignedWon) ?: "평가 가격 없음",
                )
            },
            moreNote = (data.positions.size - MAX_HOLDING_ROWS).takeIf { it > 0 }?.let { "외 ${it}종목" },
        )
    }

    private fun auto(snapshot: HomeSnapshot): AutoCard {
        val status = snapshot.auto
        return AutoCard(
            enabled = status.autoEnabled,
            stateLabel = if (status.autoEnabled) "켜짐" else "꺼짐",
            nextRun = nextRun(status),
            latestOperation = snapshot.latestOperation?.let { operation ->
                listOf(
                    KoreanLabels.operationStatus(operation.status),
                    KoreanLabels.dateTime(operation.startedAt),
                    KoreanLabels.trigger(operation.trigger),
                ).joinToString(" · ")
            } ?: NO_OPERATION,
        )
    }

    /** 07:30 is the earliest eligible time, never an exact execution time (docs/150 §20.9). */
    private fun nextRun(status: AutoScheduleStatus): String {
        if (!status.autoEnabled) return AUTO_OFF_NEXT
        val at = status.nextScheduledAt ?: return AUTO_NO_SLOT
        if (status.workState == "RUNNING") return "${KoreanLabels.dateTime(at)} 예약분 실행중"
        return "${KoreanLabels.dateTime(at)} 이후"
    }

    private fun alerts(snapshot: HomeSnapshot): List<HomeAlert> = buildList {
        val operation = snapshot.latestOperation
        if (operation?.finalCode in FINANCIAL_INTEGRITY_CODES) {
            add(HomeAlert(AlertLevel.ERROR, "재무 무결성 오류가 기록되었습니다. 모의투자 탭에서 확인이 필요합니다."))
        } else if (operation?.status == ForwardOperationStatus.FAILED) {
            add(HomeAlert(AlertLevel.ERROR, "최근 실행에 오류가 있습니다."))
        } else if (operation?.status == ForwardOperationStatus.BLOCKED) {
            add(HomeAlert(AlertLevel.ERROR, "최근 실행이 중단되었습니다."))
        } else if (operation?.status == ForwardOperationStatus.PARTIAL) {
            add(HomeAlert(AlertLevel.WARNING, "최근 실행이 일부만 처리되었습니다."))
        }
        if (snapshot.run?.summary?.status == PerformanceStatus.DATA_ERROR) {
            add(HomeAlert(AlertLevel.ERROR, "성과 데이터 검증에 실패했습니다. 성과 탭에서 확인이 필요합니다."))
        }
        if (snapshot.auto.autoEnabled && snapshot.auto.nextScheduledAt == null) {
            add(HomeAlert(AlertLevel.WARNING, "자동운영이 켜져 있지만 다음 실행이 예약되어 있지 않습니다."))
        }
        if (snapshot.auto.lastScheduleFailure != null) {
            add(HomeAlert(AlertLevel.WARNING, "자동운영 예약 기록에 실패했습니다."))
        }
    }
}
