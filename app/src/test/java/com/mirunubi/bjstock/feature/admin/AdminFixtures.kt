package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.strategy.ActiveStrategyVersion
import java.time.Instant
import java.time.LocalDate

object AdminFixtures {
    /** 2026-10-01 07:30:05 KST. */
    val NOW: Instant = Instant.parse("2026-09-30T22:30:05Z")
    val SLOT: Instant = Instant.parse("2026-10-01T22:00:00Z")

    val AUTO_ON = AutoScheduleStatus(
        autoEnabled = true,
        nextScheduleInstanceId = "auto:2026-10-02:0700:KST",
        nextScheduledAt = SLOT,
        workId = "work-1",
        workState = "ENQUEUED",
        lastScheduleFailure = null,
    )

    val AUTO_OFF = AutoScheduleStatus(
        autoEnabled = false,
        nextScheduleInstanceId = null,
        nextScheduledAt = null,
        workId = null,
        workState = null,
        lastScheduleFailure = null,
    )

    fun operation(
        id: Long,
        status: ForwardOperationStatus = ForwardOperationStatus.SUCCEEDED,
        trigger: ForwardOperationTrigger = ForwardOperationTrigger.WORKER,
        startedAt: Instant = NOW.minusSeconds(3_600L * id),
        finalCode: String? = null,
        safeMessage: String? = null,
        elapsedMs: Long? = 1_234,
        scheduleInstanceId: String? = null,
    ) = ForwardOperationEntity(
        id = id,
        operationKey = "manual:op-$id",
        trigger = trigger,
        throughDate = LocalDate.of(2026, 9, 30),
        status = status,
        startedAt = startedAt,
        finishedAt = startedAt.plusSeconds(2),
        finalCode = finalCode,
        safeMessage = safeMessage,
        runsConsidered = 2,
        runsProcessed = 1,
        runsSkipped = 1,
        cyclesCompleted = 1,
        cyclesFailed = 0,
        elapsedMs = elapsedMs,
        scheduleInstanceId = scheduleInstanceId,
    )

    fun event(
        id: Long,
        type: OperationalEventType,
        result: String? = null,
        reasonCode: String? = null,
        operationId: Long? = null,
        createdAt: Instant = NOW.minusSeconds(60L * id),
        safeMessage: String? = null,
        runId: Long? = null,
    ) = OperationalEventEntity(
        id = id,
        eventKey = "test:e$id",
        operationId = operationId,
        runId = runId,
        eventType = type,
        result = result,
        reasonCode = reasonCode,
        safeMessage = safeMessage,
        createdAt = createdAt,
    )

    fun audit(
        id: Long,
        type: TradeAuditEventType,
        runId: Long = 1,
        operationId: Long? = null,
        createdAt: Instant = NOW.minusSeconds(60L * id),
        reasonCode: String? = null,
        reasonText: String? = null,
    ) = TradeAuditLogEntity(
        id = id,
        strategyRunId = runId,
        eventType = type,
        reasonCode = reasonCode,
        reasonText = reasonText,
        eventKey = "audit:a$id",
        createdAt = createdAt,
        operationId = operationId,
    )

    fun apiError(
        id: Long,
        type: ApiErrorType = ApiErrorType.NETWORK_TIMEOUT,
        operationId: Long? = null,
        occurredAt: Instant = NOW.minusSeconds(60L * id),
        safeMessage: String = "KIS request timed out",
        httpStatus: Int? = null,
        businessCode: String? = null,
        retryable: Boolean = true,
    ) = ApiErrorLogEntity(
        id = id,
        provider = ApiErrorProvider.KIS,
        operation = "KIS_DAILY_BARS",
        errorType = type,
        httpStatus = httpStatus,
        businessCode = businessCode,
        safeMessage = safeMessage,
        retryable = retryable,
        occurredAt = occurredAt,
        operationId = operationId,
    )

    fun cycle(
        id: Long,
        status: ForwardCycleStatus = ForwardCycleStatus.FAILED,
        errorCode: String? = "NETWORK_FAILURE",
        retryable: Boolean = true,
        runId: Long = 1,
        updatedAt: Instant = NOW.minusSeconds(30L * id),
    ) = ForwardTestCycleEntity(
        id = id,
        strategyRunId = runId,
        marketDate = LocalDate.of(2026, 9, 30),
        status = status,
        currentStage = ForwardCycleStage.FACTORS,
        attemptCount = 1,
        errorCode = errorCode,
        errorMessage = "Market data sync failed",
        retryable = retryable,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    fun run(id: Long, status: RunStatus, name: String = "Run $id") = StrategyRunEntity(
        id = id,
        runName = name,
        strategyVersionId = 1,
        startDate = LocalDate.of(2026, 9, 1),
        initialCash = 10_000_000,
        status = status,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    fun statusData(
        auto: AutoScheduleStatus = AUTO_ON,
        operations: List<ForwardOperationEntity> = emptyList(),
        runs: List<StrategyRunEntity> = emptyList(),
        kis: KisPresence = KisPresence(KisEnvironment.VIRTUAL, credentialPresent = true),
    ) = AdminStatusData(auto = auto, now = NOW, recentOperations = operations, runs = runs, kis = kis)

    fun environmentData(
        kis: KisPresence = KisPresence(KisEnvironment.VIRTUAL, credentialPresent = true),
        versions: List<ActiveStrategyVersion> = listOf(ActiveStrategyVersion(7, "기본 모멘텀 전략", 2)),
        runs: List<StrategyRunEntity> = emptyList(),
    ) = AdminEnvironmentData(
        app = AppBuildInfo("com.mirunubi.bjstock", "0.1.0", 1, debuggable = true),
        databaseVersion = 12,
        kis = kis,
        activeVersions = versions,
        runs = runs,
    )
}
