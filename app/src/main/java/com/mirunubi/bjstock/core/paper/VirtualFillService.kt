package com.mirunubi.bjstock.core.paper

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PositionEntity
import com.mirunubi.bjstock.core.error.IntegrityViolationException
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import com.mirunubi.bjstock.core.model.CashLedgerReferenceTypes
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.time.Instant
import java.time.LocalDate

/**
 * Atomic paper order terminal transitions. Each call is one Room transaction:
 * - fill: order VIRTUAL_FILLED + execution + cash ledger group + position + EXECUTION_FILLED
 * - reject: order REJECTED + ORDER_REJECTED
 * - cancel: order CANCELLED + ORDER_CANCELLED
 * Snapshots are written separately (docs/150 §11).
 */
class VirtualFillService(
    private val database: BJStockDatabase,
    private val orderDao: OrderDao,
    private val executionDao: ExecutionDao,
    private val positionDao: PositionDao,
    private val cashLedger: CashLedgerService,
    private val audit: TradeAuditLogService? = null,
    private val now: () -> Instant = { Instant.now() },
    private val failAfterExecution: Boolean = false,
) {
    suspend fun executeBuy(
        order: OrderEntity,
        executionDate: LocalDate,
        executionPriceWon: Long,
        quantity: Long,
        commissionWon: Long,
    ): PaperTradeResult {
        require(order.side == OrderSide.BUY)
        require(quantity > 0L)
        require(executionPriceWon > 0L)
        val requested = requestedExecution(order, executionDate, executionPriceWon, quantity, commissionWon, 0L)

        return database.withTransaction {
            replayedFill(requested, executionDate)?.let { return@withTransaction it }
            val current = fillableOrder(order.id)
            val gross = executionPriceWon * quantity
            val totalOutflow = gross + commissionWon
            val cash = cashLedger.currentCash(current.strategyRunId)
            require(totalOutflow <= cash) { "insufficient cash for buy fill" }

            val executionId = executionDao.insert(requested)
            if (failAfterExecution) {
                error("forced fill failure after execution")
            }
            cashLedger.append(
                strategyRunId = current.strategyRunId,
                eventType = CashLedgerEventType.BUY,
                amountWon = -gross,
                eventDate = executionDate,
                referenceType = CashLedgerReferenceTypes.EXECUTION,
                referenceId = executionId,
                eventKey = CashLedgerService.buyPrincipalKey(executionId),
            )
            if (commissionWon > 0L) {
                cashLedger.append(
                    strategyRunId = current.strategyRunId,
                    eventType = CashLedgerEventType.COMMISSION,
                    amountWon = -commissionWon,
                    eventDate = executionDate,
                    referenceType = CashLedgerReferenceTypes.EXECUTION,
                    referenceId = executionId,
                    eventKey = CashLedgerService.buyCommissionKey(executionId),
                )
            }
            val existing = positionDao.find(current.strategyRunId, current.instrumentId)
            if (existing == null) {
                positionDao.insert(
                    PositionEntity(
                        strategyRunId = current.strategyRunId,
                        instrumentId = current.instrumentId,
                        quantity = quantity,
                        averagePrice = executionPriceWon,
                        realizedProfit = 0L,
                        updatedAt = now(),
                    ),
                )
            } else {
                require(existing.quantity == 0L) {
                    "Phase 6 forbids averaging into an open position"
                }
                positionDao.update(
                    existing.copy(
                        quantity = quantity,
                        averagePrice = executionPriceWon,
                        updatedAt = now(),
                    ),
                )
            }
            val filled = current.copy(
                quantity = quantity,
                status = OrderStatus.VIRTUAL_FILLED,
                executedAt = requested.executedAt,
            )
            orderDao.update(filled)
            appendFilledAudit(filled, requested.copy(id = executionId), executionDate, restore = false)
            PaperTradeResult(
                action = PaperTradeAction.FILLED,
                orderId = order.id,
                executionId = executionId,
            )
        }
    }

    suspend fun executeSell(
        order: OrderEntity,
        executionDate: LocalDate,
        executionPriceWon: Long,
        quantity: Long,
        commissionWon: Long,
        taxWon: Long,
    ): PaperTradeResult {
        require(order.side == OrderSide.SELL)
        require(quantity > 0L)
        val requested = requestedExecution(order, executionDate, executionPriceWon, quantity, commissionWon, taxWon)

        return database.withTransaction {
            replayedFill(requested, executionDate)?.let { return@withTransaction it }
            val current = fillableOrder(order.id)
            val position = positionDao.find(current.strategyRunId, current.instrumentId)
                ?: error("no position for sell")
            require(position.quantity == quantity) { "Phase 6 sells full position only" }
            val gross = executionPriceWon * quantity
            val buyCostBasis = position.averagePrice * quantity
            val netInflow = gross - commissionWon - taxWon
            val realized = netInflow - buyCostBasis

            val executionId = executionDao.insert(requested)
            if (failAfterExecution) {
                error("forced fill failure after execution")
            }
            cashLedger.append(
                strategyRunId = current.strategyRunId,
                eventType = CashLedgerEventType.SELL,
                amountWon = gross,
                eventDate = executionDate,
                referenceType = CashLedgerReferenceTypes.EXECUTION,
                referenceId = executionId,
                eventKey = CashLedgerService.sellProceedsKey(executionId),
            )
            if (commissionWon > 0L) {
                cashLedger.append(
                    strategyRunId = current.strategyRunId,
                    eventType = CashLedgerEventType.COMMISSION,
                    amountWon = -commissionWon,
                    eventDate = executionDate,
                    referenceType = CashLedgerReferenceTypes.EXECUTION,
                    referenceId = executionId,
                    eventKey = CashLedgerService.sellCommissionKey(executionId),
                )
            }
            if (taxWon > 0L) {
                cashLedger.append(
                    strategyRunId = current.strategyRunId,
                    eventType = CashLedgerEventType.TAX,
                    amountWon = -taxWon,
                    eventDate = executionDate,
                    referenceType = CashLedgerReferenceTypes.EXECUTION,
                    referenceId = executionId,
                    eventKey = CashLedgerService.sellTaxKey(executionId),
                )
            }
            positionDao.update(
                position.copy(
                    quantity = 0L,
                    averagePrice = 0L,
                    realizedProfit = position.realizedProfit + realized,
                    updatedAt = now(),
                ),
            )
            val filled = current.copy(
                quantity = quantity,
                status = OrderStatus.VIRTUAL_FILLED,
                executedAt = requested.executedAt,
            )
            orderDao.update(filled)
            appendFilledAudit(filled, requested.copy(id = executionId), executionDate, restore = false)
            PaperTradeResult(
                action = PaperTradeAction.FILLED,
                orderId = order.id,
                executionId = executionId,
            )
        }
    }

    /** PENDING_EXECUTION / CREATED → REJECTED with a canonical [reasonCode] audit, atomically. */
    suspend fun reject(
        order: OrderEntity,
        reasonCode: String,
        marketDate: LocalDate,
        message: String,
    ): PaperTradeResult = database.withTransaction {
        val current = orderDao.findById(order.id)
            ?: throw IntegrityViolationException.invariant("ORDER_NOT_FOUND")
        when (current.status) {
            OrderStatus.REJECTED -> Unit
            OrderStatus.PENDING_EXECUTION, OrderStatus.CREATED -> {
                orderDao.update(current.copy(status = OrderStatus.REJECTED, quantity = 0L))
                audit?.append(
                    strategyRunId = current.strategyRunId,
                    eventType = TradeAuditEventType.ORDER_REJECTED,
                    eventKey = TradeAuditLogService.orderRejectedKey(current.id),
                    instrumentId = current.instrumentId,
                    evaluationId = current.evaluationId,
                    orderId = current.id,
                    marketDate = marketDate,
                    reasonCode = reasonCode,
                    reasonText = "${current.side.name} rejected at next trading day open",
                )
            }
            else -> throw IntegrityViolationException.invariant("ORDER_NOT_REJECTABLE")
        }
        PaperTradeResult(
            action = PaperTradeAction.ORDER_REJECTED,
            orderId = order.id,
            message = message,
        )
    }

    /** PENDING_EXECUTION → CANCELLED with a canonical [reasonCode] audit, atomically. False if not pending. */
    suspend fun cancelPending(
        orderId: Long,
        reasonCode: String,
        marketDate: LocalDate,
        cancelledAt: Instant,
    ): Boolean = database.withTransaction {
        val current = orderDao.findById(orderId)
            ?: throw IntegrityViolationException.invariant("ORDER_NOT_FOUND")
        if (current.status != OrderStatus.PENDING_EXECUTION) return@withTransaction false
        orderDao.update(current.copy(status = OrderStatus.CANCELLED, cancelledAt = cancelledAt))
        audit?.append(
            strategyRunId = current.strategyRunId,
            eventType = TradeAuditEventType.ORDER_CANCELLED,
            eventKey = TradeAuditLogService.orderCancelledKey(current.id),
            instrumentId = current.instrumentId,
            evaluationId = current.evaluationId,
            orderId = current.id,
            marketDate = marketDate,
            reasonCode = reasonCode,
            reasonText = "${current.side.name} pending order cancelled at run end",
        )
        true
    }

    private fun requestedExecution(
        order: OrderEntity,
        executionDate: LocalDate,
        executionPriceWon: Long,
        quantity: Long,
        commissionWon: Long,
        taxWon: Long,
    ) = ExecutionEntity(
        orderId = order.id,
        executionPrice = executionPriceWon,
        quantity = quantity,
        commission = commissionWon,
        tax = taxWon,
        slippage = 0L,
        executedAt = MarketExecutionTime.of(executionDate),
        createdAt = now(),
        executionKey = executionKey(order.id),
    )

    /**
     * Returns ALREADY_FILLED when [requested] is a replay of the committed fill; null when the order
     * has not been filled. Any disagreement between order state and execution aborts.
     */
    private suspend fun replayedFill(
        requested: ExecutionEntity,
        marketDate: LocalDate,
    ): PaperTradeResult? {
        val current = orderDao.findById(requested.orderId)
            ?: throw IntegrityViolationException.invariant("ORDER_NOT_FOUND")
        val existing = executionDao.findByExecutionKey(requested.executionKey)
        if (existing == null) {
            if (current.status == OrderStatus.VIRTUAL_FILLED) {
                throw IntegrityViolationException.financial("FILLED_ORDER_WITHOUT_EXECUTION")
            }
            return null
        }
        if (current.status != OrderStatus.VIRTUAL_FILLED || current.quantity != existing.quantity) {
            throw IntegrityViolationException.financial("EXECUTION_ORDER_STATE_MISMATCH")
        }
        if (!isSameFill(existing, requested)) {
            throw IntegrityViolationException.financial("EXECUTION_REPLAY_MISMATCH")
        }
        appendFilledAudit(current, existing, marketDate, restore = true)
        return PaperTradeResult(
            action = PaperTradeAction.ALREADY_FILLED,
            orderId = current.id,
            executionId = existing.id,
            message = "order already filled",
        )
    }

    private suspend fun fillableOrder(orderId: Long): OrderEntity {
        val current = orderDao.findById(orderId)
            ?: throw IntegrityViolationException.invariant("ORDER_NOT_FOUND")
        if (current.status != OrderStatus.PENDING_EXECUTION && current.status != OrderStatus.CREATED) {
            throw IntegrityViolationException.invariant("ORDER_NOT_FILLABLE")
        }
        return current
    }

    private suspend fun appendFilledAudit(
        order: OrderEntity,
        execution: ExecutionEntity,
        marketDate: LocalDate,
        restore: Boolean,
    ) {
        val auditLog = audit ?: return
        val reasonText = "${order.side.name} ${execution.quantity} shares at next trading day open " +
            "${execution.executionPrice}"
        val eventKey = TradeAuditLogService.executionFilledKey(execution.id)
        if (restore) {
            auditLog.restoreMissing(
                strategyRunId = order.strategyRunId,
                eventType = TradeAuditEventType.EXECUTION_FILLED,
                eventKey = eventKey,
                instrumentId = order.instrumentId,
                evaluationId = order.evaluationId,
                orderId = order.id,
                executionId = execution.id,
                marketDate = marketDate,
                reasonText = reasonText,
            )
        } else {
            auditLog.append(
                strategyRunId = order.strategyRunId,
                eventType = TradeAuditEventType.EXECUTION_FILLED,
                eventKey = eventKey,
                instrumentId = order.instrumentId,
                evaluationId = order.evaluationId,
                orderId = order.id,
                executionId = execution.id,
                marketDate = marketDate,
                reasonText = reasonText,
            )
        }
    }

    private fun isSameFill(existing: ExecutionEntity, requested: ExecutionEntity): Boolean =
        existing.orderId == requested.orderId &&
            existing.executionPrice == requested.executionPrice &&
            existing.quantity == requested.quantity &&
            existing.commission == requested.commission &&
            existing.tax == requested.tax &&
            existing.slippage == requested.slippage &&
            existing.executedAt == requested.executedAt

    companion object {
        /** Paper trading has exactly one VIRTUAL_FILLED execution per order; broker fills will use `:fill:<n>`. */
        fun executionKey(orderId: Long) = "paper:order:$orderId:fill:1"
    }
}
