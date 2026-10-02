package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.ErrorCategory
import com.mirunubi.bjstock.core.error.ErrorSeverity
import com.mirunubi.bjstock.core.error.OperationAction
import com.mirunubi.bjstock.core.error.RetryPolicy
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.ForwardRunResult
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.feature.paper.PaperTradingPresenter
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.time.Instant
import java.util.Locale

// region UI state

data class AdminUiState(
    val status: SectionState<StatusView> = SectionState.Loading,
    val operations: SectionState<List<OperationRow>> = SectionState.Loading,
    val audit: SectionState<List<AuditRow>> = SectionState.Loading,
    val auditFilter: AuditFilter = AuditFilter.ALL,
    val expandedAudit: Set<String> = emptySet(),
    val errors: SectionState<List<ErrorRow>> = SectionState.Loading,
    val expandedErrors: Set<String> = emptySet(),
    val environment: SectionState<EnvironmentView> = SectionState.Loading,
    val detail: AdminDetail? = null,
)

sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>

    data class Empty(val message: String) : SectionState<Nothing>

    data class Failed(val message: String) : SectionState<Nothing>

    data class Loaded<T>(val value: T) : SectionState<T>
}

sealed interface AdminDetail {
    val operationId: Long

    data class Loading(override val operationId: Long) : AdminDetail

    data class Failed(override val operationId: Long, val message: String) : AdminDetail

    data class Loaded(override val operationId: Long, val view: OperationDetailView) : AdminDetail
}

data class AdminField(val label: String, val value: String)

/** Secondary color cue only; every badge also carries a text label and an icon. */
enum class AdminTone { NEUTRAL, POSITIVE, WARNING, ERROR }

data class StatusView(
    val autoEnabled: Boolean,
    val autoLabel: String,
    val nextRun: String,
    val nextRunPastDue: Boolean,
    val workState: String,
    val scheduleWarning: String?,
    val runningOperation: String,
    val lastSuccess: String,
    val lastProblem: String,
    val runs: String,
    val kisEnvironment: String,
    val kisCredential: String,
    val windowNote: String,
)

data class OperationRow(
    val id: Long,
    val title: String,
    val time: String,
    val trigger: String,
    val status: ForwardOperationStatus,
    val statusLabel: String,
    val code: String?,
    val message: String?,
    val safeMessage: String?,
    val elapsed: String,
)

data class OperationDetailView(
    val title: String,
    val status: ForwardOperationStatus,
    val statusLabel: String,
    val fields: List<AdminField>,
    val events: List<EventView>,
    val audits: List<AuditRow>,
)

data class EventView(
    val key: String,
    val time: String,
    val title: String,
    val result: String,
    val problem: Boolean,
    val reasonCode: String?,
    val reason: String?,
    val safeMessage: String?,
    val ids: String?,
    val elapsed: String?,
)

enum class AuditCategory(val label: String) {
    STRATEGY("전략 판단"),
    ORDER("주문 · 체결"),
    PROVIDER("Provider"),
    SCHEDULER("Scheduler"),
    OPERATION("실행"),
}

enum class AuditFilter(val label: String) {
    ALL("전체"),
    STRATEGY("전략 판단"),
    ORDER("주문 · 체결"),
    PROVIDER("Provider"),
    SCHEDULER("Scheduler"),
    ERROR("오류"),
}

data class AuditRow(
    val key: String,
    val at: Instant,
    val time: String,
    val category: AuditCategory,
    val title: String,
    val result: String,
    val problem: Boolean,
    val reason: String?,
    val run: String?,
    val operation: String?,
    val details: List<AdminField>,
)

data class ErrorRow(
    val key: String,
    val at: Instant,
    val time: String,
    val source: String,
    val category: String,
    val severity: String,
    val tone: AdminTone,
    val code: String,
    val description: String,
    val retry: String,
    val impact: String,
    val details: List<AdminField>,
)

data class EnvironmentView(
    val fields: List<AdminField>,
    val activeVersions: List<String>,
    val runs: List<String>,
)

// endregion

/** Pure mapping from read-only rows to Korean view models. Never shows keys, tokens, stack traces or raw bodies. */
object AdminPresenter {
    const val LOADING = "데이터를 불러오는 중입니다."
    const val LOAD_FAILED = "데이터를 불러오지 못했습니다."
    const val OPERATIONS_EMPTY = "최근 실행 기록이 없습니다."
    const val ERRORS_EMPTY = "조회 범위 내 최근 오류가 없습니다."
    const val AUDIT_WINDOW_NOTE = "최근 기록을 표시합니다. 항목별 조회 범위가 서로 다를 수 있습니다."
    const val ERRORS_WINDOW_NOTE = "최근 오류 기록을 표시하며, 데이터 종류별 조회 범위가 다를 수 있습니다."
    val AUDIT_WINDOW_DETAIL =
        "실행 최근 ${RoomAdminDataSource.OPERATION_LIMIT}건 · 운영 이벤트 최근 ${RoomAdminDataSource.EVENT_LIMIT}건 · " +
            "거래 Audit 최근 ${RoomAdminDataSource.AUDIT_LIMIT}건 · API 오류 최근 7일(최대 ${RoomAdminDataSource.API_ERROR_LIMIT}건)"
    val ERRORS_WINDOW_DETAIL =
        "실행 최근 ${RoomAdminDataSource.OPERATION_LIMIT}건 · 운영 이벤트 최근 ${RoomAdminDataSource.EVENT_LIMIT}건 · " +
            "API 오류 최근 7일(최대 ${RoomAdminDataSource.API_ERROR_LIMIT}건) · 거래일 처리 Run별 최근 ${RoomAdminDataSource.CYCLE_LIMIT}건"
    const val AUDIT_EMPTY = "Audit 기록이 없습니다."
    const val EVENTS_EMPTY = "관련 이벤트가 없습니다."
    const val DETAIL_NOT_FOUND = "실행 기록을 찾을 수 없습니다."
    const val WITHHELD = "보안상 상세 내용을 표시하지 않습니다."
    const val NONE_IN_WINDOW = "최근 20건 중 없음"
    const val NO_ACTIVE_VERSIONS = "사용 중인 전략 버전이 없습니다."
    const val NO_ACTIVE_RUNS = "실행 준비 또는 운영 중인 Run이 없습니다."
    const val UNCLASSIFIED = "미분류"
    private const val WINDOW_NOTE = "최근 실행 20건 기준"
    private const val SCHEDULE_FAILURE = "최근 자동 예약 변경을 기록하지 못했습니다."
    private const val DASH = "-"

    private val SUCCESS_STATUSES = setOf(ForwardOperationStatus.SUCCEEDED, ForwardOperationStatus.NO_OP)
    private val PROBLEM_STATUSES = setOf(ForwardOperationStatus.FAILED, ForwardOperationStatus.BLOCKED)
    private val PROBLEM_RESULTS = setOf(ForwardOperationStatus.FAILED.name, ForwardOperationStatus.BLOCKED.name)
    private val ACTIVE_RUN_STATUSES = listOf(RunStatus.READY, RunStatus.RUNNING)

    /** Operation lifecycle and cycle failures are shown from forward_operations and forward_test_cycles instead. */
    private val ERROR_EVENT_TYPES = setOf(
        OperationalEventType.RUN_RESULT,
        OperationalEventType.MARKET_SYNC_RESULT,
        OperationalEventType.WORKER_SCHEDULE_CHANGED,
    )

    // region 운영 상태

    fun status(data: AdminStatusData): StatusView {
        val (nextRun, pastDue) = PaperTradingPresenter.slot(data.auto, data.now)
        val operations = data.recentOperations
        return StatusView(
            autoEnabled = data.auto.autoEnabled,
            autoLabel = if (data.auto.autoEnabled) "켜짐" else "꺼짐",
            nextRun = nextRun,
            nextRunPastDue = pastDue,
            workState = workState(data.auto.workState, data.auto.autoEnabled),
            scheduleWarning = data.auto.lastScheduleFailure?.let { SCHEDULE_FAILURE },
            runningOperation = operations.firstOrNull { it.status == ForwardOperationStatus.RUNNING }
                ?.let { "실행 중 · ${dateTime(it.startedAt)} 시작" }
                ?: "없음",
            lastSuccess = operations.firstOrNull { it.status in SUCCESS_STATUSES }
                ?.let { "${dateTime(it.startedAt)} · ${operationStatus(it.status)}" }
                ?: NONE_IN_WINDOW,
            lastProblem = operations.firstOrNull { it.status in PROBLEM_STATUSES }
                ?.let { op -> listOfNotNull(dateTime(op.startedAt), operationStatus(op.status), safeCode(op.finalCode)).joinToString(" · ") }
                ?: NONE_IN_WINDOW,
            runs = runCounts(data.runs.map { it.status }),
            kisEnvironment = kisEnvironment(data.kis.environment),
            kisCredential = kisCredential(data.kis.credentialPresent),
            windowNote = WINDOW_NOTE,
        )
    }

    fun workState(state: String?, autoEnabled: Boolean): String = when (state) {
        null -> if (autoEnabled) "예약된 작업 없음" else "예약 없음 (자동운영 꺼짐)"
        "ENQUEUED" -> "대기 중 (ENQUEUED)"
        "RUNNING" -> "실행 중 (RUNNING)"
        "BLOCKED" -> "선행 조건 대기 (BLOCKED)"
        else -> safeCode(state) ?: DASH
    }

    fun runCounts(statuses: List<RunStatus>): String {
        val counts = ACTIVE_RUN_STATUSES.map { status -> status to statuses.count { it == status } }
        if (counts.all { it.second == 0 }) return NO_ACTIVE_RUNS
        return counts.joinToString(" · ") { (status, count) -> "${KoreanLabels.runStatus(status)} ${count}개" }
    }

    fun kisEnvironment(environment: KisEnvironment): String = when (environment) {
        KisEnvironment.VIRTUAL -> "모의투자 (VIRTUAL)"
        KisEnvironment.PRODUCTION -> "실전투자 (PRODUCTION)"
    }

    fun kisCredential(present: Boolean): String = if (present) "있음" else "없음"

    // endregion

    // region 최근 실행

    fun operations(operations: List<ForwardOperationEntity>): SectionState<List<OperationRow>> =
        if (operations.isEmpty()) SectionState.Empty(OPERATIONS_EMPTY) else SectionState.Loaded(operations.map(::operationRow))

    fun operationRow(operation: ForwardOperationEntity) = OperationRow(
        id = operation.id,
        title = PaperTradingPresenter.operationTitle(operation.trigger, operation.operationKind),
        time = dateTime(operation.startedAt),
        trigger = KoreanLabels.trigger(operation.trigger),
        status = operation.status,
        statusLabel = operationStatus(operation.status),
        code = safeCode(operation.finalCode),
        message = codeMessage(operation.finalCode),
        safeMessage = safeText(operation.safeMessage),
        elapsed = elapsed(operation.elapsedMs),
    )

    /** Operator wording for 운영 · 감사; product screens keep [KoreanLabels.operationStatus]. */
    fun operationStatus(status: ForwardOperationStatus): String = when (status) {
        ForwardOperationStatus.RUNNING -> "실행 중"
        ForwardOperationStatus.SUCCEEDED -> "성공"
        ForwardOperationStatus.NO_OP -> "변경 없음"
        ForwardOperationStatus.PARTIAL -> "부분 완료"
        ForwardOperationStatus.BLOCKED -> "차단"
        ForwardOperationStatus.FAILED -> "실패"
    }

    fun statusTone(status: ForwardOperationStatus): AdminTone = when (status) {
        ForwardOperationStatus.SUCCEEDED -> AdminTone.POSITIVE
        ForwardOperationStatus.RUNNING, ForwardOperationStatus.NO_OP -> AdminTone.NEUTRAL
        ForwardOperationStatus.PARTIAL -> AdminTone.WARNING
        ForwardOperationStatus.BLOCKED, ForwardOperationStatus.FAILED -> AdminTone.ERROR
    }

    // endregion

    // region Operation detail

    fun detail(data: AdminOperationDetailData): OperationDetailView {
        val op = data.operation
        return OperationDetailView(
            title = "${PaperTradingPresenter.operationTitle(op.trigger, op.operationKind)} #${op.id}",
            status = op.status,
            statusLabel = operationStatus(op.status),
            fields = listOf(
                AdminField("ID", "#${op.id}"),
                AdminField("트리거", "${KoreanLabels.trigger(op.trigger)} (${op.trigger.name})"),
                AdminField("작업 종류", operationKind(op.operationKind)),
                AdminField("상태", operationStatus(op.status)),
                AdminField("기준일", PaperTradingPresenter.dotDate(op.throughDate)),
                AdminField("시작", dateTime(op.startedAt)),
                AdminField("종료", op.finishedAt?.let(::dateTime) ?: "진행 중 또는 기록 없음"),
                AdminField("소요 시간", elapsed(op.elapsedMs)),
                AdminField("결과 코드", safeCode(op.finalCode) ?: DASH),
                AdminField("결과 설명", codeMessage(op.finalCode) ?: DASH),
                AdminField("안전 메시지", safeText(op.safeMessage) ?: DASH),
                AdminField("Run 처리", "대상 ${op.runsConsidered} · 처리 ${op.runsProcessed} · 건너뜀 ${op.runsSkipped}"),
                AdminField("거래일 처리", "완료 ${op.cyclesCompleted} · 실패 ${op.cyclesFailed}"),
                AdminField("예약 ID", scheduleInstanceId(op.scheduleInstanceId) ?: DASH),
            ),
            events = data.events.map(::event),
            audits = data.audits.map(::auditRow),
        )
    }

    fun operationKind(kind: ForwardOperationKind): String = when (kind) {
        ForwardOperationKind.FORWARD_RUN -> "포워드 실행"
        ForwardOperationKind.RETRY_FAILED_CYCLE -> "실패 거래일 재처리"
    }

    fun event(event: OperationalEventEntity) = EventView(
        key = "event:${event.id}",
        time = dateTime(event.createdAt),
        title = eventType(event.eventType),
        result = resultLabel(event.result),
        problem = event.result in PROBLEM_RESULTS,
        reasonCode = safeCode(event.reasonCode),
        reason = codeMessage(event.reasonCode),
        safeMessage = safeText(event.safeMessage),
        ids = ids(event.runId, event.cycleId, event.instrumentId),
        elapsed = event.elapsedMs?.let(::elapsed),
    )

    fun eventType(type: OperationalEventType): String = when (type) {
        OperationalEventType.OPERATION_STARTED -> "실행 시작"
        OperationalEventType.OPERATION_FINISHED -> "실행 종료"
        OperationalEventType.MARKET_SYNC_RESULT -> "시세 동기화"
        OperationalEventType.RUN_RESULT -> "Run 처리 결과"
        OperationalEventType.CYCLE_STARTED -> "거래일 처리 시작"
        OperationalEventType.CYCLE_FINISHED -> "거래일 처리 종료"
        OperationalEventType.WORKER_SCHEDULE_CHANGED -> "자동 예약 변경"
    }

    /** `operational_events.result` values written by the forward and scheduler paths; unknown codes are shown as-is. */
    fun resultLabel(result: String?): String {
        if (result == null) return DASH
        ForwardOperationStatus.entries.firstOrNull { it.name == result }?.let { return operationStatus(it) }
        return when (result) {
            ForwardRunResult.PROCESSED.name -> "처리됨"
            ForwardRunResult.SKIPPED.name -> "건너뜀"
            "SUCCESS" -> "성공"
            "COMPLETE" -> "완료"
            ForwardOperationTrigger.MANUAL.name -> "수동"
            ForwardOperationTrigger.WORKER.name -> "자동"
            ForwardTestScheduler.SLOT_ENQUEUED -> "예약됨"
            ForwardTestScheduler.AUTO_DISABLED -> "자동 꺼짐"
            ForwardTestScheduler.LEGACY_PERIODIC_CANCELLED -> "이전 주기 작업 취소"
            else -> safeCode(result) ?: DASH
        }
    }

    fun ids(runId: Long?, cycleId: Long?, instrumentId: Long?): String? = listOfNotNull(
        runId?.let { "Run #$it" },
        cycleId?.let { "Cycle #$it" },
        instrumentId?.let { "종목 #$it" },
    ).joinToString(" · ").ifEmpty { null }

    // endregion

    // region Audit

    fun audit(data: AdminAuditData): SectionState<List<AuditRow>> {
        val rows = (
            data.audits.map(::auditRow) +
                data.events.map(::eventAuditRow) +
                data.operations.map(::operationAuditRow) +
                data.apiErrors.map(::apiErrorAuditRow)
            ).sortedWith(compareByDescending<AuditRow> { it.at }.thenBy { it.key })
        return if (rows.isEmpty()) SectionState.Empty(AUDIT_EMPTY) else SectionState.Loaded(rows)
    }

    fun filter(rows: List<AuditRow>, filter: AuditFilter): List<AuditRow> = rows.filter { matches(it, filter) }

    fun matches(row: AuditRow, filter: AuditFilter): Boolean = when (filter) {
        AuditFilter.ALL -> true
        AuditFilter.STRATEGY -> row.category == AuditCategory.STRATEGY
        AuditFilter.ORDER -> row.category == AuditCategory.ORDER
        AuditFilter.PROVIDER -> row.category == AuditCategory.PROVIDER
        AuditFilter.SCHEDULER -> row.category == AuditCategory.SCHEDULER
        AuditFilter.ERROR -> row.problem
    }

    fun auditCategory(type: TradeAuditEventType): AuditCategory = when (type) {
        TradeAuditEventType.RULE_TRIGGERED, TradeAuditEventType.EVALUATION_DECIDED -> AuditCategory.STRATEGY
        TradeAuditEventType.ORDER_CREATED,
        TradeAuditEventType.ORDER_SKIPPED,
        TradeAuditEventType.ORDER_REJECTED,
        TradeAuditEventType.ORDER_CANCELLED,
        TradeAuditEventType.EXECUTION_FILLED,
        -> AuditCategory.ORDER
    }

    fun auditCategory(type: OperationalEventType): AuditCategory = when (type) {
        OperationalEventType.MARKET_SYNC_RESULT -> AuditCategory.PROVIDER
        OperationalEventType.WORKER_SCHEDULE_CHANGED -> AuditCategory.SCHEDULER
        OperationalEventType.OPERATION_STARTED,
        OperationalEventType.OPERATION_FINISHED,
        OperationalEventType.RUN_RESULT,
        OperationalEventType.CYCLE_STARTED,
        OperationalEventType.CYCLE_FINISHED,
        -> AuditCategory.OPERATION
    }

    /** Automatic executions are started by the scheduler; manual ones are plain operations. */
    fun auditCategory(trigger: ForwardOperationTrigger): AuditCategory = when (trigger) {
        ForwardOperationTrigger.WORKER -> AuditCategory.SCHEDULER
        ForwardOperationTrigger.MANUAL -> AuditCategory.OPERATION
    }

    fun auditResult(type: TradeAuditEventType): String = when (type) {
        TradeAuditEventType.RULE_TRIGGERED -> "발생"
        TradeAuditEventType.EVALUATION_DECIDED -> "판단"
        TradeAuditEventType.ORDER_CREATED -> "생성"
        TradeAuditEventType.ORDER_SKIPPED -> "건너뜀"
        TradeAuditEventType.ORDER_REJECTED -> "거절"
        TradeAuditEventType.ORDER_CANCELLED -> "취소"
        TradeAuditEventType.EXECUTION_FILLED -> "체결"
    }

    fun decisionSource(source: DecisionSource): String = when (source) {
        DecisionSource.SIGNAL_RULE -> "신호 규칙"
        DecisionSource.FACTOR_STRATEGY -> "팩터 전략"
    }

    fun auditRow(log: TradeAuditLogEntity) = AuditRow(
        key = "audit:${log.id}",
        at = log.createdAt,
        time = dateTime(log.createdAt),
        category = auditCategory(log.eventType),
        title = PaperTradingPresenter.auditEvent(log.eventType),
        result = auditResult(log.eventType),
        problem = false,
        reason = codeMessage(log.reasonCode) ?: safeText(log.reasonText),
        run = "Run #${log.strategyRunId}",
        operation = log.operationId?.let { "Operation #$it" },
        details = listOfNotNull(
            AdminField("판단 근거", log.decisionSource?.let(::decisionSource) ?: DASH),
            AdminField("사유 코드", safeCode(log.reasonCode) ?: DASH),
            AdminField("사유", safeText(log.reasonText) ?: DASH),
            AdminField("지표", safeCode(log.metricCode) ?: DASH),
            AdminField("관측값", safeText(log.observedValue) ?: DASH),
            AdminField("기준값", safeText(log.thresholdValue) ?: DASH),
            log.marketDate?.let { AdminField("거래일", PaperTradingPresenter.dotDate(it)) },
            ids(null, null, log.instrumentId)?.let { AdminField("대상", it) },
        ),
    )

    fun eventAuditRow(event: OperationalEventEntity) = AuditRow(
        key = "event:${event.id}",
        at = event.createdAt,
        time = dateTime(event.createdAt),
        category = auditCategory(event.eventType),
        title = eventType(event.eventType),
        result = resultLabel(event.result),
        problem = event.result in PROBLEM_RESULTS,
        reason = codeMessage(event.reasonCode) ?: safeCode(event.reasonCode),
        run = event.runId?.let { "Run #$it" },
        operation = event.operationId?.let { "Operation #$it" },
        details = listOfNotNull(
            AdminField("안전 메시지", safeText(event.safeMessage) ?: DASH),
            AdminField("사유 코드", safeCode(event.reasonCode) ?: DASH),
            AdminField("소요 시간", elapsed(event.elapsedMs)),
            ids(event.runId, event.cycleId, event.instrumentId)?.let { AdminField("대상", it) },
            event.marketDate?.let { AdminField("거래일", PaperTradingPresenter.dotDate(it)) },
        ),
    )

    fun operationAuditRow(op: ForwardOperationEntity) = AuditRow(
        key = "operation:${op.id}",
        at = op.startedAt,
        time = dateTime(op.startedAt),
        category = auditCategory(op.trigger),
        title = PaperTradingPresenter.operationTitle(op.trigger, op.operationKind),
        result = operationStatus(op.status),
        problem = op.status in PROBLEM_STATUSES,
        reason = codeMessage(op.finalCode) ?: safeCode(op.finalCode),
        run = null,
        operation = "Operation #${op.id}",
        details = listOf(
            AdminField("안전 메시지", safeText(op.safeMessage) ?: DASH),
            AdminField("결과 코드", safeCode(op.finalCode) ?: DASH),
            AdminField("소요 시간", elapsed(op.elapsedMs)),
            AdminField("Run 처리", "대상 ${op.runsConsidered} · 처리 ${op.runsProcessed} · 건너뜀 ${op.runsSkipped}"),
            AdminField("거래일 처리", "완료 ${op.cyclesCompleted} · 실패 ${op.cyclesFailed}"),
        ),
    )

    fun apiErrorAuditRow(error: ApiErrorLogEntity) = AuditRow(
        key = "api:${error.id}",
        at = error.occurredAt,
        time = dateTime(error.occurredAt),
        category = AuditCategory.PROVIDER,
        title = "KIS API 오류",
        result = "오류",
        problem = true,
        reason = apiErrorType(error.errorType),
        run = error.strategyRunId?.let { "Run #$it" },
        operation = error.operationId?.let { "Operation #$it" },
        details = apiErrorDetails(error),
    )

    // endregion

    // region 오류

    fun errors(data: AdminErrorData): SectionState<List<ErrorRow>> {
        val rows = (
            data.apiErrors.map(::apiErrorRow) +
                data.operations.filter { it.status in PROBLEM_STATUSES }.map(::operationErrorRow) +
                data.failedCycles.map(::cycleErrorRow) +
                data.events.filter { it.eventType in ERROR_EVENT_TYPES && it.result in PROBLEM_RESULTS }.map(::eventErrorRow)
            ).sortedWith(compareByDescending<ErrorRow> { it.at }.thenBy { it.key })
        return if (rows.isEmpty()) SectionState.Empty(ERRORS_EMPTY) else SectionState.Loaded(rows)
    }

    /**
     * Resolves a persisted code to the canonical catalog. Codes that are neither [AppErrorCode] nor [ForwardErrorCode]
     * names (e.g. ForwardOutcomeReason) return null rather than being reported as UNEXPECTED_EXCEPTION.
     */
    fun appErrorCode(code: String?): AppErrorCode? = when {
        code == null -> null
        AppErrorCode.entries.any { it.name == code } -> AppErrorCode.fromCode(code)
        ForwardErrorCode.entries.any { it.name == code } -> AppErrorMapper.fromForwardErrorCodeName(code)
        else -> null
    }

    fun errorCategory(category: ErrorCategory): String = when (category) {
        ErrorCategory.DOMAIN -> "업무 규칙"
        ErrorCategory.EXTERNAL -> "외부 서비스"
        ErrorCategory.TRANSIENT -> "일시적 오류"
        ErrorCategory.SECURITY -> "인증 설정"
        ErrorCategory.INVARIANT -> "데이터 무결성"
        ErrorCategory.UNEXPECTED -> "예상하지 못한 오류"
    }

    fun severity(severity: ErrorSeverity): String = when (severity) {
        ErrorSeverity.INFO -> "정보"
        ErrorSeverity.WARNING -> "주의"
        ErrorSeverity.DEGRADED -> "성능 저하"
        ErrorSeverity.ERROR -> "오류"
        ErrorSeverity.CRITICAL -> "심각"
        ErrorSeverity.FINANCIAL_INTEGRITY -> "재무 무결성"
    }

    fun severityTone(severity: ErrorSeverity): AdminTone = when (severity) {
        ErrorSeverity.INFO -> AdminTone.NEUTRAL
        ErrorSeverity.WARNING, ErrorSeverity.DEGRADED -> AdminTone.WARNING
        ErrorSeverity.ERROR, ErrorSeverity.CRITICAL, ErrorSeverity.FINANCIAL_INTEGRITY -> AdminTone.ERROR
    }

    fun retry(code: AppErrorCode): String = when {
        code.isRetryableAutomatically -> "자동 재시도 가능"
        code.retryPolicy == RetryPolicy.USER_ACTION_REQUIRED -> "사용자 조치 후 재시도"
        else -> "재시도하지 않음"
    }

    fun impact(action: OperationAction): String = when (action) {
        OperationAction.CONTINUE -> "계속 진행"
        OperationAction.SKIP -> "해당 항목 건너뜀"
        OperationAction.BLOCK_RUN -> "해당 Run 처리 차단"
        OperationAction.RETRY_OPERATION -> "작업 재시도 대상"
        OperationAction.ABORT_OPERATION -> "작업 중단"
        OperationAction.REQUIRE_USER_ACTION -> "사용자 조치 필요"
    }

    fun apiErrorType(type: ApiErrorType): String = when (type) {
        ApiErrorType.NETWORK_TIMEOUT -> "네트워크 응답 시간 초과"
        ApiErrorType.HTTP_ERROR -> "HTTP 오류"
        ApiErrorType.AUTH_ERROR -> "인증 오류"
        ApiErrorType.KIS_BUSINESS_ERROR -> "KIS 업무 오류"
        ApiErrorType.MALFORMED_RESPONSE -> "응답 형식 오류"
        ApiErrorType.MASTER_DOWNLOAD_ERROR -> "종목 마스터 다운로드 오류"
        ApiErrorType.RATE_LIMIT -> "요청 한도 초과"
        ApiErrorType.LOCAL_INVARIANT -> "로컬 검증 오류"
        ApiErrorType.UNEXPECTED -> "예상하지 못한 오류"
    }

    /** api_error_logs carry an ApiErrorType, not a catalog code, so severity is not inferred. */
    fun apiErrorRow(error: ApiErrorLogEntity) = ErrorRow(
        key = "api:${error.id}",
        at = error.occurredAt,
        time = dateTime(error.occurredAt),
        source = "KIS API",
        category = "외부 서비스 호출",
        severity = UNCLASSIFIED,
        tone = AdminTone.WARNING,
        code = error.errorType.name,
        description = apiErrorType(error.errorType),
        retry = if (error.retryable) "재시도 가능으로 기록됨" else "재시도 불가로 기록됨",
        impact = error.operationId?.let { "Operation #$it 중 발생" } ?: "실행과 연결되지 않은 호출",
        details = apiErrorDetails(error),
    )

    fun operationErrorRow(op: ForwardOperationEntity): ErrorRow {
        val fallbackImpact = if (op.status == ForwardOperationStatus.BLOCKED) "실행 차단" else "실행 실패"
        return codeRow(
            key = "operation:${op.id}",
            at = op.finishedAt ?: op.startedAt,
            source = "실행 · ${PaperTradingPresenter.operationTitle(op.trigger, op.operationKind)}",
            code = op.finalCode,
            fallbackImpact = fallbackImpact,
            fallbackRetry = null,
            details = listOfNotNull(
                AdminField("상태", operationStatus(op.status)),
                AdminField("안전 메시지", safeText(op.safeMessage) ?: DASH),
                AdminField("Operation", "#${op.id}"),
            ),
        )
    }

    fun cycleErrorRow(cycle: ForwardTestCycleEntity) = codeRow(
        key = "cycle:${cycle.id}",
        at = cycle.updatedAt,
        source = "거래일 처리 · ${PaperTradingPresenter.dotDate(cycle.marketDate)}",
        code = cycle.errorCode,
        fallbackImpact = "Run #${cycle.strategyRunId} 다음 거래일 처리 차단",
        fallbackRetry = if (cycle.retryable) "실패 재시도 가능으로 기록됨" else "재시도 불가로 기록됨",
        details = listOf(
            AdminField("안전 메시지", safeText(cycle.errorMessage) ?: DASH),
            AdminField("시도 횟수", "${cycle.attemptCount}회"),
            AdminField("Run", "#${cycle.strategyRunId}"),
            AdminField("Cycle", "#${cycle.id}"),
        ),
    )

    fun eventErrorRow(event: OperationalEventEntity) = codeRow(
        key = "event:${event.id}",
        at = event.createdAt,
        source = eventType(event.eventType),
        code = event.reasonCode,
        fallbackImpact = resultLabel(event.result),
        fallbackRetry = null,
        details = listOfNotNull(
            AdminField("안전 메시지", safeText(event.safeMessage) ?: DASH),
            event.operationId?.let { AdminField("Operation", "#$it") },
            ids(event.runId, event.cycleId, event.instrumentId)?.let { AdminField("대상", it) },
        ),
    )

    private fun codeRow(
        key: String,
        at: Instant,
        source: String,
        code: String?,
        fallbackImpact: String,
        fallbackRetry: String?,
        details: List<AdminField>,
    ): ErrorRow {
        val catalog = appErrorCode(code)
        val description = codeMessage(code) ?: if (code == null) "기록된 오류 코드가 없습니다." else "알 수 없는 코드입니다."
        return ErrorRow(
            key = key,
            at = at,
            time = dateTime(at),
            source = source,
            category = catalog?.let { errorCategory(it.category) } ?: "실행 결과",
            severity = catalog?.let { severity(it.severity) } ?: UNCLASSIFIED,
            tone = catalog?.let { severityTone(it.severity) } ?: AdminTone.WARNING,
            code = safeCode(code) ?: DASH,
            description = description,
            retry = fallbackRetry ?: catalog?.let(::retry) ?: "기록 없음",
            impact = catalog?.let { impact(it.operationAction) } ?: fallbackImpact,
            details = details,
        )
    }

    private fun apiErrorDetails(error: ApiErrorLogEntity): List<AdminField> = listOfNotNull(
        AdminField("안전 메시지", safeText(error.safeMessage) ?: DASH),
        AdminField("호출", safeLogicalName(error.operation) ?: DASH),
        AdminField("유형", error.errorType.name),
        error.httpStatus?.takeIf { it in 100..599 }?.let { AdminField("HTTP 상태", it.toString()) },
        SafeLogText.businessCode(error.businessCode)?.let { AdminField("업무 코드", it) },
        AdminField("재시도 가능", if (error.retryable) "예" else "아니오"),
        error.operationId?.let { AdminField("Operation", "#$it") },
        error.strategyRunId?.let { AdminField("Run", "#$it") },
        error.forwardCycleId?.let { AdminField("Cycle", "#$it") },
    )

    // endregion

    // region 앱 정보 · 환경

    fun environment(data: AdminEnvironmentData): EnvironmentView = EnvironmentView(
        fields = listOf(
            AdminField("패키지", data.app.packageName),
            AdminField("버전 이름", data.app.versionName ?: DASH),
            AdminField("버전 코드", data.app.versionCode.toString()),
            AdminField("디버그 빌드", if (data.app.debuggable) "예" else "아니오"),
            AdminField("DB 버전", data.databaseVersion.toString()),
            AdminField("KIS 환경", kisEnvironment(data.kis.environment)),
            AdminField("KIS 인증정보", kisCredential(data.kis.credentialPresent)),
        ),
        activeVersions = data.activeVersions.map { "${it.strategyName} v${it.versionNo}" },
        runs = data.runs.filter { it.status in ACTIVE_RUN_STATUSES }
            .sortedWith(compareBy<StrategyRunEntity> { ACTIVE_RUN_STATUSES.indexOf(it.status) }.thenBy { it.id })
            .map { "${it.runName} · ${KoreanLabels.runStatus(it.status)}" },
    )

    // endregion

    // region Formatting and safety

    /** e.g. `10월 1일 오전 7:30:05` in Asia/Seoul. */
    fun dateTime(instant: Instant): String =
        "${KoreanLabels.dateTime(instant)}:${"%02d".format(instant.atZone(KoreanLabels.SEOUL).second)}"

    fun elapsed(ms: Long?): String = when {
        ms == null || ms < 0 -> DASH
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.ROOT, "%.1f초", ms / 1_000.0)
        else -> "${ms / 60_000}분 ${(ms % 60_000) / 1_000}초"
    }

    /** Korean message for a canonical code, or null when the code is unknown or a success marker. */
    fun codeMessage(code: String?): String? = code?.takeIf(SafeLogText::isCode)?.let(PaperTradingPresenter::codeMessage)

    fun safeCode(code: String?): String? = code?.takeIf(SafeLogText::isCode)

    /** Defence in depth over already-sanitized text: secret-like content is withheld, length is bounded. */
    fun safeText(raw: String?): String? {
        val normalized = raw?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized == SafeLogText.WITHHELD) return WITHHELD
        if (ApiErrorLogService.sanitize(normalized) != normalized.take(SANITIZE_LIMIT)) return WITHHELD
        return normalized.take(SafeLogText.MAX_MESSAGE_LENGTH)
    }

    private fun safeLogicalName(name: String): String? = name.takeIf { LOGICAL_NAME.matches(it) }

    private fun scheduleInstanceId(id: String?): String? =
        id?.let { runCatching { SafeLogText.scheduleInstanceId(it) }.getOrNull() }

    private const val SANITIZE_LIMIT = 500
    private val LOGICAL_NAME = Regex("^[A-Za-z][A-Za-z0-9_.:/-]{0,63}$")

    // endregion
}
