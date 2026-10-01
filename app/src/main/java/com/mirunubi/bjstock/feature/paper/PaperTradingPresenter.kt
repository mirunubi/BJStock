package com.mirunubi.bjstock.feature.paper

import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.TradingPolicyView
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.forward.ForwardOperationOutcome
import com.mirunubi.bjstock.core.forward.ForwardOrchestratorResult
import com.mirunubi.bjstock.core.model.AdditionalBuyPolicy
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.SellPolicy
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.strategy.ActiveStrategyVersion
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// region UI state

data class PaperTradingUiState(
    val runs: RunsState = RunsState.Loading,
    val selectedRunId: Long? = null,
    val detail: DetailState = DetailState.None,
    val automation: AutomationState = AutomationState.Loading,
    val policyExpanded: Boolean = false,
    val universeSearch: UniverseSearch = UniverseSearch(),
    val dialog: PaperDialog? = null,
    val notice: PaperNotice? = null,
    val busy: Boolean = false,
)

data class PaperNotice(val message: String, val isError: Boolean)

sealed interface RunsState {
    data object Loading : RunsState

    data class Failed(val message: String) : RunsState

    data class Loaded(val rows: List<RunRow>) : RunsState
}

data class RunStatusBadge(val status: RunStatus, val label: String)

data class RunRow(
    val runId: Long,
    val name: String,
    val strategyLabel: String,
    val badge: RunStatusBadge,
    val startLabel: String,
    val assetLabel: String,
)

sealed interface DetailState {
    data object None : DetailState

    data object Loading : DetailState

    data class Failed(val message: String) : DetailState

    data class Loaded(val view: RunDetailView) : DetailState
}

data class LabeledValue(val label: String, val value: String)

data class RunHeader(
    val name: String,
    val strategyName: String,
    val versionLabel: String,
    val badge: RunStatusBadge,
    val period: String,
    val initialCash: String,
)

data class AccountView(val rows: List<LabeledValue>, val note: String?, val warning: String?)

data class HoldingView(
    val name: String,
    val symbol: String,
    val quantity: String,
    val averagePrice: String,
    val latestClose: String,
    val marketValue: String,
    val pricePnl: String,
)

data class ExecutionView(val side: OrderSide, val headline: String, val detail: String, val costs: String)

data class OrderView(val side: OrderSide, val status: OrderStatus, val headline: String, val detail: String)

data class UniverseItem(val instrumentId: Long, val label: String)

data class ThemeOption(val themeId: Long, val name: String)

data class UniverseView(
    val editable: Boolean,
    val items: List<UniverseItem>,
    val themes: List<ThemeOption>,
    val lockedNote: String?,
)

data class CycleView(val status: ForwardCycleStatus, val headline: String, val detail: String?)

sealed interface RetryView {
    data class Retryable(val cycleId: Long, val marketDate: LocalDate, val label: String, val reason: String?) : RetryView

    data class NotRetryable(val message: String, val reason: String?, val code: String?) : RetryView
}

data class TimelineView(val headline: String, val reason: String?)

data class RunDetailView(
    val runId: Long,
    val status: RunStatus,
    val header: RunHeader,
    val account: AccountView,
    val holdings: List<HoldingView>,
    val executions: List<ExecutionView>,
    val orders: List<OrderView>,
    val policy: List<LabeledValue>?,
    val universe: UniverseView,
    val canMarkReady: Boolean,
    val cycles: List<CycleView>,
    val retry: RetryView?,
    val timeline: List<TimelineView>,
    val readyConfirmation: ReadyConfirmationView,
)

data class ReadyConfirmationView(val rows: List<LabeledValue>)

sealed interface AutomationState {
    data object Loading : AutomationState

    data class Failed(val message: String) : AutomationState

    data class Loaded(val view: AutomationView) : AutomationState
}

data class AutomationView(
    val enabled: Boolean,
    val stateLabel: String,
    val slotLine: String,
    val slotPastDue: Boolean,
    val warning: String?,
    val operations: List<OperationView>,
)

data class OperationStatusBadge(val status: ForwardOperationStatus, val label: String)

data class OperationView(
    val title: String,
    val badge: OperationStatusBadge,
    val timing: String,
    val throughDate: String,
    val message: String?,
    val code: String?,
    val safeMessage: String?,
)

data class UniverseSearch(val query: String = "", val results: List<UniverseItem> = emptyList())

data class VersionOption(val strategyVersionId: Long, val label: String)

sealed interface PaperDialog {
    data class ConfirmAuto(val enable: Boolean) : PaperDialog

    data object ConfirmRunNow : PaperDialog

    data class ConfirmRetry(val runId: Long, val cycleId: Long, val marketDate: LocalDate) : PaperDialog

    data class ConfirmReady(val runId: Long, val rows: List<LabeledValue>) : PaperDialog

    data class CreateDraft(
        val versions: List<VersionOption>,
        val selectedVersionId: Long?,
        val name: String,
        val startDate: String,
        val initialCash: String,
        val error: String? = null,
    ) : PaperDialog
}

// endregion

/** Pure mapping from read data to Korean presentation. No calculation beyond formatting. */
object PaperTradingPresenter {
    const val RUNS_EMPTY = "아직 모의투자가 없습니다. '새 모의투자'로 시작해 주세요."
    const val RUNS_FAILED = "모의투자 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val DETAIL_FAILED = "모의투자 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val AUTOMATION_FAILED = "자동운영 정보를 불러오지 못했습니다."
    const val NO_SNAPSHOT_NOTE = "아직 평가 기록이 없어 초기자금을 표시합니다."
    const val DATA_ERROR_WARNING = "모의계좌 기록 검증에 실패했습니다. 설정 > 개발자 도구에서 확인이 필요합니다."
    const val HOLDINGS_EMPTY = "보유 종목이 없습니다."
    const val PRICE_PNL_NOTE = "평가손익은 가격 기준이며 수수료·세금은 포함하지 않습니다."
    const val EXECUTIONS_EMPTY = "아직 가상 체결이 없습니다."
    const val ORDERS_EMPTY = "아직 모의주문이 없습니다."
    const val POLICY_PENDING = "거래 정책은 운영 준비를 완료할 때 고정됩니다."
    const val UNIVERSE_EMPTY = "투자 대상 종목이 없습니다."
    const val UNIVERSE_LOCKED = "실행 준비가 완료된 모의투자의 투자 대상은 변경할 수 없습니다."
    const val CYCLES_EMPTY = "아직 처리한 거래일이 없습니다."
    const val TIMELINE_EMPTY = "아직 기록된 활동이 없습니다."
    const val OPERATIONS_EMPTY = "아직 실행 기록이 없습니다."
    const val NOT_RETRYABLE = "자동 복구할 수 없는 오류입니다."
    const val AUTO_OFF_LINE = "자동운영이 꺼져 있습니다."
    const val AUTO_NO_SLOT = "예약된 자동 실행 작업이 없습니다."
    const val AUTO_SCHEDULE_FAILURE = "자동운영 예약 기록에 실패했습니다."
    const val NO_ACTIVE_VERSION = "사용 중인 전략 버전이 없습니다. 전략 탭에서 버전을 사용 시작해 주세요."
    const val VERSION_REQUIRED = "전략 버전을 선택해 주세요."
    const val NAME_REQUIRED = "모의투자 이름을 입력해 주세요."
    const val DATE_INVALID = "시작일을 2026-10-01 형식으로 입력해 주세요."
    const val CASH_INVALID = "초기자금은 0보다 큰 금액으로 입력해 주세요."
    const val DEFAULT_INITIAL_CASH = "100000000"
    const val GENERIC_FAILURE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."

    const val AUTO_ON_TITLE = "자동 모의투자를 켜시겠습니까?"
    const val AUTO_ON_BODY =
        "매일 오전 7:00 이후 네트워크가 가능할 때 포워드 테스트가 실행됩니다. 자동운영을 켜도 지금 즉시 실행되지는 않습니다."
    const val AUTO_OFF_TITLE = "자동 모의투자를 끄시겠습니까?"
    const val AUTO_OFF_BODY = "예약된 자동 실행 작업이 취소됩니다."
    const val RUN_NOW_TITLE = "지금 실행하시겠습니까?"
    const val RUN_NOW_BODY =
        "현재까지 수집 가능한 시세를 기준으로 모의투자 포워드 테스트를 실행합니다. 실제 주식 주문은 발생하지 않습니다. " +
            "모의 평가·주문·체결·계좌 기록은 생성될 수 있습니다."
    const val READY_TITLE = "운영 준비를 완료하시겠습니까?"
    const val READY_BODY = "운영 준비를 완료하면 투자 대상과 거래 정책이 고정되고, 초기자금이 모의계좌에 반영됩니다."
    const val RETRY_TITLE = "실패한 날짜를 다시 처리하시겠습니까?"

    private val RUN_RANK = listOf(
        RunStatus.RUNNING,
        RunStatus.READY,
        RunStatus.PAUSED,
        RunStatus.DRAFT,
        RunStatus.COMPLETED,
        RunStatus.CANCELLED,
    )

    // region Run list and header

    fun runStatus(status: RunStatus): String = when (status) {
        RunStatus.DRAFT -> "설정중"
        RunStatus.READY -> "실행 준비"
        RunStatus.RUNNING -> "운영 중"
        RunStatus.PAUSED -> "일시정지"
        RunStatus.COMPLETED -> "완료"
        RunStatus.CANCELLED -> "취소"
    }

    fun badge(status: RunStatus) = RunStatusBadge(status, runStatus(status))

    /** All Runs, none filtered: RUNNING, READY, PAUSED, DRAFT, COMPLETED, CANCELLED, then newest first. */
    fun sortRuns(runs: List<PaperRunData>): List<PaperRunData> =
        runs.sortedWith(compareBy<PaperRunData> { RUN_RANK.indexOf(it.run.status) }.thenByDescending { it.run.id })

    fun runRows(runs: List<PaperRunData>): List<RunRow> = sortRuns(runs).map { data ->
        val summary = data.summary
        val total = summary.latestTotalAsset
        RunRow(
            runId = data.run.id,
            name = data.run.runName,
            strategyLabel = "${data.strategyName} ${data.versionLabel}",
            badge = badge(data.run.status),
            startLabel = "${dotDate(data.run.startDate)}부터",
            assetLabel = if (total != null) {
                "총 모의자산 ${won(total)}" + (summary.cumulativeReturn?.let { " · ${PerformanceMath.formatSignedPercent(it)}" } ?: "")
            } else {
                "초기자금 ${won(data.run.initialCash)} · 평가 전"
            },
        )
    }

    fun defaultSelection(runs: List<PaperRunData>): Long? = sortRuns(runs).firstOrNull()?.run?.id

    fun header(data: PaperRunData) = RunHeader(
        name = data.run.runName,
        strategyName = data.strategyName,
        versionLabel = data.versionLabel,
        badge = badge(data.run.status),
        period = "${dotDate(data.run.startDate)}부터" + (data.run.endDate?.let { " · ${dotDate(it)}까지" } ?: ""),
        initialCash = won(data.run.initialCash),
    )

    // endregion

    // region Detail

    fun detail(data: PaperRunDetailData): RunDetailView {
        val run = data.run.run
        val draft = run.status == RunStatus.DRAFT
        val bySymbol = data.instruments.values.associateBy { it.symbol }
        return RunDetailView(
            runId = run.id,
            status = run.status,
            header = header(data.run),
            account = account(data.run),
            holdings = data.positions.map { position ->
                HoldingView(
                    name = data.instruments[position.instrumentId]?.name ?: position.symbol,
                    symbol = position.symbol,
                    quantity = shares(position.quantity),
                    averagePrice = won(position.averagePrice),
                    latestClose = position.latestClose?.let(::won) ?: NO_PRICE,
                    marketValue = position.marketValue?.let(::won) ?: NO_PRICE,
                    pricePnl = position.unrealizedPricePnl?.let(::signedWon) ?: NO_PRICE,
                )
            },
            executions = data.executions.mapNotNull { execution ->
                val orderSide = OrderSide.entries.firstOrNull { it.name == execution.side } ?: return@mapNotNull null
                val name = bySymbol[execution.symbol]?.name ?: execution.symbol
                ExecutionView(
                    side = orderSide,
                    headline = "${shortDate(execution.executionDate)} · ${side(orderSide)} · $VIRTUAL_FILL",
                    detail = "$name ${shares(execution.quantity)} × ${won(execution.price)}",
                    costs = listOfNotNull(
                        "수수료 ${won(execution.commission)}",
                        execution.tax.takeIf { it > 0L }?.let { "매도세 ${won(it)}" },
                    ).joinToString(" · "),
                )
            },
            orders = data.orders.take(ORDER_ROWS).map { order ->
                val name = data.instruments[order.instrumentId]?.name ?: order.instrumentId.toString()
                OrderView(
                    side = order.side,
                    status = order.status,
                    headline = "${shortDate(order.createdAt)} · ${side(order.side)} · ${orderStatus(order.status)}",
                    detail = "$name ${shares(order.quantity)}",
                )
            },
            policy = data.policy?.let(::policy),
            universe = UniverseView(
                editable = draft,
                items = data.universe.map { UniverseItem(it.instrumentId, instrumentLabel(it.name, it.symbol)) },
                themes = data.themes.map { ThemeOption(it.id, it.name) },
                lockedNote = if (draft) null else UNIVERSE_LOCKED,
            ),
            canMarkReady = draft,
            cycles = data.cycles.map(::cycle),
            retry = data.failedCycle?.let(::retry),
            timeline = data.timeline.map { timeline(it, data.instruments) },
            readyConfirmation = ReadyConfirmationView(
                listOf(
                    LabeledValue("모의투자 이름", run.runName),
                    LabeledValue("전략", "${data.run.strategyName} ${data.run.versionLabel}"),
                    LabeledValue("시작일", dotDate(run.startDate)),
                    LabeledValue("초기자금", won(run.initialCash)),
                    LabeledValue("투자 대상", "${data.universe.size}종목"),
                ),
            ),
        )
    }

    /** Figures come only from PerformanceAnalyticsService; without a snapshot the initial capital is shown, never 0원. */
    fun account(data: PaperRunData): AccountView {
        val summary = data.summary
        val warning = if (summary.status == PerformanceStatus.DATA_ERROR) DATA_ERROR_WARNING else null
        val total = summary.latestTotalAsset
        if (total == null || warning != null) {
            return AccountView(
                rows = listOf(
                    LabeledValue("총 모의자산", won(data.run.initialCash)),
                    LabeledValue("현금", NOT_VALUED),
                    LabeledValue("보유주식 평가액", NOT_VALUED),
                    LabeledValue("누적 손익", NOT_VALUED),
                    LabeledValue("누적 수익률", NOT_VALUED),
                ),
                note = NO_SNAPSHOT_NOTE,
                warning = warning,
            )
        }
        return AccountView(
            rows = listOf(
                LabeledValue("총 모의자산", won(total)),
                LabeledValue("현금", summary.latestCash?.let(::won) ?: NOT_VALUED),
                LabeledValue("보유주식 평가액", summary.latestMarketValue?.let(::won) ?: NOT_VALUED),
                LabeledValue("누적 손익", summary.cumulativeProfit?.let(::signedWon) ?: NOT_VALUED),
                LabeledValue("누적 수익률", summary.cumulativeReturn?.let(PerformanceMath::formatSignedPercent) ?: NOT_VALUED),
            ),
            note = null,
            warning = null,
        )
    }

    fun side(side: OrderSide): String = when (side) {
        OrderSide.BUY -> "매수"
        OrderSide.SELL -> "매도"
    }

    fun orderStatus(status: OrderStatus): String = when (status) {
        OrderStatus.CREATED -> "생성"
        OrderStatus.PENDING_EXECUTION -> "체결 대기"
        OrderStatus.VIRTUAL_FILLED -> VIRTUAL_FILL
        OrderStatus.CANCELLED -> "취소"
        OrderStatus.REJECTED -> "거절"
    }

    /** Renders the stored snapshot values of the Run; nothing here is a default. */
    fun policy(view: TradingPolicyView): List<LabeledValue> = listOf(
        LabeledValue("1회 매수 비중", percent(view.buyAllocationRate)),
        LabeledValue("수수료 가정", percent(view.commissionRate)),
        LabeledValue("매도세 가정", percent(view.sellTaxRate)),
        LabeledValue("슬리피지", if (view.slippageBps == 0L) "없음 (0bp)" else "${view.slippageBps}bp"),
        LabeledValue(
            "체결가격 정책",
            when (view.executionPricePolicy) {
                ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN.name -> "다음 거래일 시가"
                else -> view.executionPricePolicy
            },
        ),
        LabeledValue(
            "추가매수",
            when (view.additionalBuyPolicy) {
                AdditionalBuyPolicy.DISALLOW.name -> "허용 안 함"
                else -> view.additionalBuyPolicy
            },
        ),
        LabeledValue(
            "매도방식",
            when (view.sellPolicy) {
                SellPolicy.FULL_POSITION.name -> "전량 매도"
                else -> view.sellPolicy
            },
        ),
        LabeledValue("공매도", if (view.shortSellingAllowed) "허용" else "허용 안 함"),
        LabeledValue("정책 버전", view.policyVersion),
    )

    fun cycleStatus(status: ForwardCycleStatus): String = when (status) {
        ForwardCycleStatus.PENDING -> "대기"
        ForwardCycleStatus.RUNNING -> "처리 중"
        ForwardCycleStatus.COMPLETE -> "완료"
        ForwardCycleStatus.FAILED -> "실패"
    }

    fun cycle(cycle: ForwardTestCycleEntity) = CycleView(
        status = cycle.status,
        headline = "${shortDate(cycle.marketDate)} · ${cycleStatus(cycle.status)}",
        detail = listOfNotNull(
            "시도 ${cycle.attemptCount}회".takeIf { cycle.attemptCount > 0 },
            cycle.errorCode?.let { codeMessage(it) ?: "오류 코드 $it" },
        ).joinToString(" · ").ifEmpty { null },
    )

    /** Only a real FAILED cycle produces a retry block; retryable=false never offers an action. */
    fun retry(cycle: ForwardTestCycleEntity): RetryView? {
        if (cycle.status != ForwardCycleStatus.FAILED) return null
        return if (cycle.retryable) {
            RetryView.Retryable(
                cycleId = cycle.id,
                marketDate = cycle.marketDate,
                label = "실패한 날짜 다시 처리 (${dotDate(cycle.marketDate)})",
                reason = cycle.errorCode?.let(::codeMessage),
            )
        } else {
            RetryView.NotRetryable(
                message = NOT_RETRYABLE,
                reason = cycle.errorCode?.let(::codeMessage),
                code = cycle.errorCode,
            )
        }
    }

    fun retryBody(marketDate: LocalDate): String =
        "${dotDate(marketDate)} 거래일 처리를 다시 시도합니다. 실제 주식 주문은 발생하지 않습니다. " +
            "모의 평가·주문·체결·계좌 기록은 생성될 수 있습니다."

    fun auditEvent(type: TradeAuditEventType): String = when (type) {
        TradeAuditEventType.RULE_TRIGGERED -> "신호 규칙 발생"
        TradeAuditEventType.EVALUATION_DECIDED -> "전략 판단"
        TradeAuditEventType.ORDER_CREATED -> "모의주문 생성"
        TradeAuditEventType.ORDER_SKIPPED -> "주문 건너뜀"
        TradeAuditEventType.ORDER_REJECTED -> "주문 거절"
        TradeAuditEventType.ORDER_CANCELLED -> "주문 취소"
        TradeAuditEventType.EXECUTION_FILLED -> VIRTUAL_FILL
    }

    fun timeline(event: TradeAuditLogEntity, instruments: Map<Long, InstrumentLabel>): TimelineView {
        val date = event.marketDate?.let(::shortDate) ?: shortDate(event.createdAt)
        val instrument = event.instrumentId?.let { instruments[it]?.name }
        return TimelineView(
            headline = listOfNotNull(date, auditEvent(event.eventType), instrument).joinToString(" · "),
            reason = event.reasonText?.takeIf { it.isNotBlank() }?.take(REASON_MAX),
        )
    }

    // endregion

    // region Automation

    fun automation(data: PaperAutomationData): AutomationView {
        val status = data.status
        val (slotLine, pastDue) = slot(status, data.now)
        return AutomationView(
            enabled = status.autoEnabled,
            stateLabel = if (status.autoEnabled) "켜짐" else "꺼짐",
            slotLine = slotLine,
            slotPastDue = pastDue,
            warning = status.lastScheduleFailure?.let { AUTO_SCHEDULE_FAILURE },
            operations = data.operations.map(::operation),
        )
    }

    /**
     * 07:00 is the earliest eligible time, never an exact execution time. A slot whose time has passed is
     * shown as waiting, never as a future time; no backoff timing is inferred.
     */
    fun slot(status: AutoScheduleStatus, now: Instant): Pair<String, Boolean> {
        if (!status.autoEnabled) return AUTO_OFF_LINE to false
        val at = status.nextScheduledAt ?: return AUTO_NO_SLOT to false
        val label = KoreanLabels.dateTime(at)
        return when {
            status.workState == RUNNING_STATE -> "$label 예약 작업 · 실행 중" to true
            !at.isAfter(now) -> "$label 예약 작업 · 실행/재시도 대기 중" to true
            else -> "다음 자동 실행 $label 이후" to false
        }
    }

    fun operationTitle(trigger: ForwardOperationTrigger, kind: ForwardOperationKind): String = when {
        kind == ForwardOperationKind.RETRY_FAILED_CYCLE -> "실패 재시도"
        trigger == ForwardOperationTrigger.WORKER -> "자동 실행"
        else -> "수동 실행"
    }

    fun operationStatus(status: ForwardOperationStatus): String = when (status) {
        ForwardOperationStatus.RUNNING -> "실행 중"
        ForwardOperationStatus.SUCCEEDED -> "완료"
        ForwardOperationStatus.NO_OP -> "처리할 항목 없음"
        ForwardOperationStatus.PARTIAL -> "일부 처리"
        ForwardOperationStatus.BLOCKED -> "실행 차단"
        ForwardOperationStatus.FAILED -> "실패"
    }

    /** operation_key, work_id and schedule instance ids are never shown. */
    fun operation(operation: ForwardOperationEntity) = OperationView(
        title = operationTitle(operation.trigger, operation.operationKind),
        badge = OperationStatusBadge(operation.status, operationStatus(operation.status)),
        timing = listOfNotNull(
            "시작 ${KoreanLabels.dateTime(operation.startedAt)}",
            operation.finishedAt?.let { "종료 ${KoreanLabels.dateTime(it)}" },
        ).joinToString(" · "),
        throughDate = "기준일 ${dotDate(operation.throughDate)}",
        message = operation.finalCode?.let(::codeMessage),
        code = operation.finalCode,
        safeMessage = operation.safeMessage?.takeIf { it.isNotBlank() }?.take(SAFE_MESSAGE_MAX),
    )

    /** Result of a confirmed Run Now / retry, from the outcome's status and canonical code only. */
    fun outcome(outcome: ForwardOperationOutcome): PaperNotice {
        val status = operationStatus(outcome.status)
        val detail = when (val display = outcome.display) {
            is ForwardOrchestratorResult.Ok ->
                display.processedDates.size.takeIf { it > 0 }?.let { "${it}개 거래일 처리" }
            else -> outcome.finalCode?.let(::codeMessage)
        }
        val error = outcome.status == ForwardOperationStatus.BLOCKED || outcome.status == ForwardOperationStatus.FAILED
        return PaperNotice(listOfNotNull("실행 결과: $status", detail).joinToString(" · "), isError = error)
    }

    // endregion

    // region Errors

    /** Korean message for a persisted canonical code (ForwardOutcomeReason, ForwardErrorCode or AppErrorCode). */
    fun codeMessage(code: String): String? {
        ForwardOutcomeReason.entries.firstOrNull { it.name == code }?.let { return outcomeReason(it) }
        val known = ForwardErrorCode.entries.any { it.name == code } || AppErrorCode.entries.any { it.name == code }
        if (!known) return null
        return appError(AppErrorMapper.fromForwardErrorCodeName(code))
    }

    fun outcomeReason(reason: ForwardOutcomeReason): String? = when (reason) {
        ForwardOutcomeReason.PROCESSED -> null
        ForwardOutcomeReason.NO_ELIGIBLE_RUNS -> "실행할 모의투자가 없습니다."
        ForwardOutcomeReason.THROUGH_DATE_BEFORE_START -> "아직 시작일 전이라 처리할 거래일이 없습니다."
        ForwardOutcomeReason.EMPTY_UNIVERSE -> "투자 대상 종목이 없습니다."
        ForwardOutcomeReason.MISSING_POLICY -> "거래 정책이 없습니다."
        ForwardOutcomeReason.AUTH_REQUIRED -> AUTH_MESSAGE
        ForwardOutcomeReason.PREVIOUS_FAILED_CYCLE -> "이전에 실패한 거래일이 있어 먼저 다시 처리해야 합니다."
        ForwardOutcomeReason.WAITING_FOR_MARKET_DATA -> "새 시세 데이터를 기다리는 중입니다."
        ForwardOutcomeReason.INVALID_RUN_STATE -> "처리할 수 없는 모의투자 상태입니다."
        ForwardOutcomeReason.RUN_NOT_FOUND -> "모의투자를 찾을 수 없습니다."
        ForwardOutcomeReason.PRIOR_RUN_BLOCKED -> "앞선 전략 실행이 중단되어 이 전략은 실행하지 않았습니다."
        ForwardOutcomeReason.ALREADY_RUNNING -> "다른 자동/수동 작업이 이미 실행 중입니다."
        ForwardOutcomeReason.CANCELLED -> "작업이 끝나기 전에 취소되었습니다."
        ForwardOutcomeReason.PROCESS_INTERRUPTED -> "앱이 종료되어 작업이 중단되었습니다."
        ForwardOutcomeReason.TARGET_CYCLE_NOT_FOUND,
        ForwardOutcomeReason.TARGET_CYCLE_NOT_FAILED,
        ForwardOutcomeReason.TARGET_CYCLE_RUN_MISMATCH,
        -> "다시 처리할 실패 거래일을 찾지 못했습니다."
    }

    fun appError(code: AppErrorCode): String = when (code) {
        AppErrorCode.NO_POSITION_TO_SELL -> "매도할 보유 종목이 없어 주문을 건너뛰었습니다."
        AppErrorCode.POSITION_ALREADY_OPEN -> "이미 보유 중이라 추가매수를 건너뛰었습니다."
        AppErrorCode.INSUFFICIENT_CASH -> "모의 현금이 부족해 주문이 거절되었습니다."
        AppErrorCode.INSUFFICIENT_WARMUP_DATA -> WARMUP_MESSAGE
        AppErrorCode.INVALID_RUN_STATE -> "처리할 수 없는 모의투자 상태입니다."
        AppErrorCode.EMPTY_UNIVERSE -> "투자 대상 종목이 없습니다."
        AppErrorCode.MISSING_TRADING_POLICY -> "거래 정책이 없습니다."
        AppErrorCode.SNAPSHOT_MISSING_PRICE -> "보유 종목의 가격이 없어 평가하지 못했습니다."
        AppErrorCode.CYCLE_FAILED -> "실패한 거래일이 있어 다시 처리해야 합니다."
        AppErrorCode.KIS_SERVER_ERROR -> "KIS 서버 오류로 시세를 받지 못했습니다."
        AppErrorCode.KIS_BUSINESS_ERROR -> "KIS가 요청을 거부했습니다."
        AppErrorCode.KIS_MALFORMED_RESPONSE -> "KIS 응답을 읽을 수 없습니다."
        AppErrorCode.NETWORK_UNAVAILABLE -> "네트워크에 연결할 수 없어 실행하지 못했습니다."
        AppErrorCode.NETWORK_TIMEOUT -> "네트워크 응답 시간이 초과되었습니다."
        AppErrorCode.KIS_RATE_LIMIT -> "KIS 요청 한도에 도달했습니다."
        AppErrorCode.CREDENTIAL_MISSING,
        AppErrorCode.CREDENTIAL_REJECTED,
        AppErrorCode.AUTH_REQUIRED,
        -> AUTH_MESSAGE
        AppErrorCode.DATA_INTEGRITY_ERROR -> "로컬 데이터 검증에 실패했습니다."
        AppErrorCode.LEDGER_MISMATCH,
        AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT,
        AppErrorCode.FILLED_ORDER_WITHOUT_EXECUTION,
        -> "모의계좌 재무 무결성 오류입니다. 확인이 필요합니다."
        AppErrorCode.INTERNAL_INVARIANT_VIOLATION -> "내부 검증에 실패했습니다."
        AppErrorCode.UNEXPECTED_EXCEPTION -> "예상하지 못한 오류가 발생했습니다."
    }

    /**
     * markReady failures. StrategyRunService carries a ForwardErrorCode name as the exception message for
     * INVALID_STATE; it is only compared with known names, never displayed.
     */
    fun readyFailure(failure: Throwable): String {
        if (failure !is StrategyVersionException) return GENERIC_FAILURE
        return when (failure.kind) {
            StrategyErrorKind.VERSION_NOT_ACTIVE -> "사용 중인 전략 버전이 필요합니다."
            StrategyErrorKind.NOT_FOUND -> "모의투자를 찾을 수 없습니다."
            StrategyErrorKind.INVALID_STATE -> when (failure.message) {
                ForwardErrorCode.EMPTY_UNIVERSE.name -> "투자 대상 종목을 하나 이상 추가해 주세요."
                ForwardErrorCode.AUTH_REQUIRED.name -> AUTH_MESSAGE
                ForwardErrorCode.INSUFFICIENT_WARMUP_DATA.name -> "전략 계산에 필요한 과거 시세 데이터가 부족합니다."
                else -> "운영 준비를 완료할 수 없는 상태입니다. 모의투자 설정을 확인해 주세요."
            }
            else -> GENERIC_FAILURE
        }
    }

    fun draftFailure(failure: Throwable): String = when {
        failure is StrategyVersionException && failure.kind == StrategyErrorKind.VERSION_NOT_ACTIVE ->
            "사용 중인 전략 버전만 선택할 수 있습니다."
        failure is StrategyVersionException && failure.kind == StrategyErrorKind.NOT_FOUND -> "전략 버전을 찾을 수 없습니다."
        failure is IllegalArgumentException -> "입력값을 확인해 주세요."
        else -> GENERIC_FAILURE
    }

    fun universeFailure(failure: Throwable): String = when {
        failure is StrategyVersionException && failure.kind == StrategyErrorKind.INVALID_STATE -> UNIVERSE_LOCKED
        failure is StrategyVersionException && failure.kind == StrategyErrorKind.NOT_FOUND -> "종목 또는 테마를 찾을 수 없습니다."
        else -> GENERIC_FAILURE
    }

    /** A failure that escaped the coordinator (e.g. recording the operation); mapped by type only. */
    fun operationFailure(failure: Throwable): String {
        val code = AppErrorMapper.fromThrowable(failure).code
        return "실행하지 못했습니다. ${appError(code)}"
    }

    fun autoFailure(enable: Boolean): String =
        if (enable) "자동운영을 켜지 못했습니다. 잠시 후 다시 시도해 주세요." else "자동운영을 끄지 못했습니다. 잠시 후 다시 시도해 주세요."

    // endregion

    // region Draft dialog

    fun versionOptions(versions: List<ActiveStrategyVersion>): List<VersionOption> =
        versions.map { VersionOption(it.strategyVersionId, "${it.strategyName} V${it.versionNo}") }

    fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim().replace('.', '-')) }.getOrNull()

    fun parseWon(raw: String): Long? =
        raw.trim().replace(",", "").removeSuffix("원").trim().toLongOrNull()?.takeIf { it > 0L }

    // endregion

    // region Formatting

    fun won(value: Long): String = "%,d원".format(value)

    fun signedWon(value: Long): String = if (value > 0L) "+${won(value)}" else won(value)

    fun shares(quantity: Long): String = "%,d주".format(quantity)

    /** e.g. `2026.09.18`. */
    fun dotDate(date: LocalDate): String = "%04d.%02d.%02d".format(date.year, date.monthValue, date.dayOfMonth)

    /** e.g. `09.29`. */
    fun shortDate(date: LocalDate): String = "%02d.%02d".format(date.monthValue, date.dayOfMonth)

    fun shortDate(instant: Instant): String = shortDate(instant.atZone(KoreanLabels.SEOUL).toLocalDate())

    fun percent(rate: BigDecimal): String =
        rate.multiply(BigDecimal(100)).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString() + "%"

    fun instrumentLabel(name: String, symbol: String) = "$name $symbol"

    // endregion

    private const val VIRTUAL_FILL = "가상 체결"
    private const val NO_PRICE = "가격 없음"
    private const val NOT_VALUED = "평가 전"
    private const val AUTH_MESSAGE = "KIS 연결 설정을 확인해 주세요."
    private const val WARMUP_MESSAGE = "전략 계산에 필요한 과거 시세 데이터가 부족합니다."
    private const val RUNNING_STATE = "RUNNING"
    private const val ORDER_ROWS = 10
    private const val REASON_MAX = 120
    private const val SAFE_MESSAGE_MAX = 160
}
