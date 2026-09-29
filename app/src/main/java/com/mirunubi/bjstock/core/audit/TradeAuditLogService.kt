package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.database.dao.TradeAuditLogDao
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.error.IntegrityViolationException
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.time.Instant
import java.time.LocalDate

class TradeAuditLogService(
    private val dao: TradeAuditLogDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun append(
        strategyRunId: Long,
        eventType: TradeAuditEventType,
        eventKey: String,
        instrumentId: Long? = null,
        evaluationId: Long? = null,
        orderId: Long? = null,
        executionId: Long? = null,
        marketDate: LocalDate? = null,
        decisionSource: DecisionSource? = null,
        ruleId: Long? = null,
        reasonCode: String? = null,
        reasonText: String? = null,
        metricCode: String? = null,
        observedValue: String? = null,
        thresholdValue: String? = null,
        correlateWithCurrentOperation: Boolean = true,
    ): Long {
        dao.findByEventKey(eventKey)?.let { existing ->
            return requireSameEvent(existing, strategyRunId, eventType, evaluationId, orderId, executionId)
        }
        val insertedId = dao.insert(
            TradeAuditLogEntity(
                strategyRunId = strategyRunId,
                instrumentId = instrumentId,
                evaluationId = evaluationId,
                orderId = orderId,
                executionId = executionId,
                marketDate = marketDate,
                eventType = eventType,
                decisionSource = decisionSource,
                ruleId = ruleId,
                reasonCode = reasonCode,
                reasonText = reasonText,
                metricCode = metricCode,
                observedValue = observedValue,
                thresholdValue = thresholdValue,
                eventKey = eventKey,
                createdAt = now(),
                operationId = if (correlateWithCurrentOperation) currentForwardOperationId() else null,
            ),
        )
        if (insertedId != -1L) return insertedId
        val raced = dao.findByEventKey(eventKey)
            ?: throw IntegrityViolationException.invariant("AUDIT_EVENT_KEY_INSERT_IGNORED")
        return requireSameEvent(raced, strategyRunId, eventType, evaluationId, orderId, executionId)
    }

    /**
     * Restores a missing audit row for a business row committed before audit atomicity.
     * The row is uncorrelated (operation_id NULL): the current operation did not produce the event.
     */
    suspend fun restoreMissing(
        strategyRunId: Long,
        eventType: TradeAuditEventType,
        eventKey: String,
        instrumentId: Long? = null,
        evaluationId: Long? = null,
        orderId: Long? = null,
        executionId: Long? = null,
        marketDate: LocalDate? = null,
        reasonText: String? = null,
    ): Long = append(
        strategyRunId = strategyRunId,
        eventType = eventType,
        eventKey = eventKey,
        instrumentId = instrumentId,
        evaluationId = evaluationId,
        orderId = orderId,
        executionId = executionId,
        marketDate = marketDate,
        reasonCode = RECONCILED_REASON_CODE,
        reasonText = reasonText,
        correlateWithCurrentOperation = false,
    )

    private fun requireSameEvent(
        existing: TradeAuditLogEntity,
        strategyRunId: Long,
        eventType: TradeAuditEventType,
        evaluationId: Long?,
        orderId: Long?,
        executionId: Long?,
    ): Long {
        val sameEvent = existing.strategyRunId == strategyRunId &&
            existing.eventType == eventType &&
            existing.evaluationId == evaluationId &&
            existing.orderId == orderId &&
            existing.executionId == executionId
        if (!sameEvent) throw IntegrityViolationException.invariant("AUDIT_EVENT_KEY_CONFLICT")
        return existing.id
    }

    suspend fun findByRun(strategyRunId: Long) = dao.findByRun(strategyRunId)

    suspend fun findRecentByRun(strategyRunId: Long, limit: Int = 100) =
        dao.findRecentByRun(strategyRunId, limit)

    companion object {
        const val RECONCILED_REASON_CODE = "LEGACY_AUDIT_RESTORED"

        fun evaluationDecisionKey(evaluationId: Long) = "evaluation:$evaluationId:decision"
        fun ruleTriggeredKey(evaluationId: Long, ruleId: Long) =
            "evaluation:$evaluationId:rule:$ruleId:triggered"
        fun orderCreatedKey(orderId: Long) = "order:$orderId:created"
        fun orderSkippedKey(evaluationId: Long, reasonCode: String) =
            "evaluation:$evaluationId:skipped:$reasonCode"
        fun orderRejectedKey(orderId: Long) = "order:$orderId:rejected"
        fun orderCancelledKey(orderId: Long) = "order:$orderId:cancelled"
        fun executionFilledKey(executionId: Long) = "execution:$executionId:filled"
    }
}
