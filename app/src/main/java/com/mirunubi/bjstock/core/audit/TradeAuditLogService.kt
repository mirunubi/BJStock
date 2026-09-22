package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.database.dao.TradeAuditLogDao
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
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
    ): Long {
        val existing = dao.findByEventKey(eventKey)
        if (existing != null) return existing.id
        return dao.insert(
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
            ),
        )
    }

    suspend fun findByRun(strategyRunId: Long) = dao.findByRun(strategyRunId)

    suspend fun findRecentByRun(strategyRunId: Long, limit: Int = 100) =
        dao.findRecentByRun(strategyRunId, limit)

    companion object {
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
