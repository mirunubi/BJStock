package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import java.time.Instant

class ProcessEvaluationUseCase(
    private val evaluationDao: StockEvaluationDao,
    private val strategyRunDao: StrategyRunDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val audit: TradeAuditLogService? = null,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend operator fun invoke(evaluationId: Long): PaperTradeResult {
        val evaluation = evaluationDao.findEvaluationById(evaluationId)
            ?: return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                message = "evaluation not found",
            )
        val run = strategyRunDao.findById(evaluation.strategyRunId)
            ?: return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                message = "strategy run not found",
            )
        if (run.status !in setOf(RunStatus.READY, RunStatus.RUNNING)) {
            return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                message = "run status ${run.status} cannot trade",
            )
        }

        val decision = evaluation.quantDecision
        if (decision == TradeDecision.HOLD || decision == TradeDecision.NO_ACTION) {
            return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                message = "decision $decision creates no order",
            )
        }

        val side = when (decision) {
            TradeDecision.BUY -> OrderSide.BUY
            TradeDecision.SELL -> OrderSide.SELL
            else -> return PaperTradeResult(action = PaperTradeAction.NO_TRADE)
        }

        val existing = orderDao.findByEvaluationAndSide(evaluationId, side)
        if (existing != null) {
            return PaperTradeResult(
                action = PaperTradeAction.ORDER_ALREADY_EXISTS,
                orderId = existing.id,
                message = "order already exists for evaluation/side",
            )
        }

        val position = positionDao.find(evaluation.strategyRunId, evaluation.instrumentId)
        val openQty = position?.quantity ?: 0L

        when (side) {
            OrderSide.BUY -> {
                if (openQty > 0L) {
                    audit?.append(
                        strategyRunId = evaluation.strategyRunId,
                        eventType = TradeAuditEventType.ORDER_SKIPPED,
                        eventKey = TradeAuditLogService.orderSkippedKey(
                            evaluationId,
                            "POSITION_ALREADY_OPEN",
                        ),
                        instrumentId = evaluation.instrumentId,
                        evaluationId = evaluationId,
                        marketDate = evaluation.evaluationDate,
                        reasonCode = "POSITION_ALREADY_OPEN",
                        reasonText = "BUY skipped: position already open",
                    )
                    return PaperTradeResult(
                        action = PaperTradeAction.ORDER_SKIPPED,
                        message = "POSITION_ALREADY_OPEN",
                    )
                }
            }
            OrderSide.SELL -> {
                if (openQty <= 0L) {
                    audit?.append(
                        strategyRunId = evaluation.strategyRunId,
                        eventType = TradeAuditEventType.ORDER_SKIPPED,
                        eventKey = TradeAuditLogService.orderSkippedKey(
                            evaluationId,
                            "NO_POSITION_TO_SELL",
                        ),
                        instrumentId = evaluation.instrumentId,
                        evaluationId = evaluationId,
                        marketDate = evaluation.evaluationDate,
                        reasonCode = "NO_POSITION_TO_SELL",
                        reasonText = "SELL skipped: no position to sell",
                    )
                    return PaperTradeResult(
                        action = PaperTradeAction.ORDER_SKIPPED,
                        message = "NO_POSITION_TO_SELL",
                    )
                }
            }
        }

        val clientOrderId = clientOrderId(evaluation.strategyRunId, evaluationId, side)
        val quantity = if (side == OrderSide.SELL) openQty else 0L
        val orderId = orderDao.insert(
            OrderEntity(
                clientOrderId = clientOrderId,
                strategyRunId = evaluation.strategyRunId,
                instrumentId = evaluation.instrumentId,
                evaluationId = evaluationId,
                side = side,
                orderType = OrderType.MARKET,
                requestedPrice = null,
                quantity = quantity,
                status = OrderStatus.PENDING_EXECUTION,
                createdAt = now(),
            ),
        )
        audit?.append(
            strategyRunId = evaluation.strategyRunId,
            eventType = TradeAuditEventType.ORDER_CREATED,
            eventKey = TradeAuditLogService.orderCreatedKey(orderId),
            instrumentId = evaluation.instrumentId,
            evaluationId = evaluationId,
            orderId = orderId,
            marketDate = evaluation.evaluationDate,
            reasonText = "${side.name} signal → PENDING_EXECUTION",
        )
        return PaperTradeResult(
            action = PaperTradeAction.ORDER_CREATED,
            orderId = orderId,
            message = "PENDING_EXECUTION",
        )
    }

    companion object {
        fun clientOrderId(strategyRunId: Long, evaluationId: Long, side: OrderSide): String =
            "paper-run-$strategyRunId-eval-$evaluationId-${side.name}"
    }
}
