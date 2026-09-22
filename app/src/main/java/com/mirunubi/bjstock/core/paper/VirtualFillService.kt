package com.mirunubi.bjstock.core.paper

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PositionEntity
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import com.mirunubi.bjstock.core.model.CashLedgerReferenceTypes
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Atomic virtual fill: order status + execution + cash ledger + position.
 */
class VirtualFillService(
    private val database: BJStockDatabase,
    private val orderDao: OrderDao,
    private val executionDao: ExecutionDao,
    private val positionDao: PositionDao,
    private val cashLedger: CashLedgerService,
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
        if (order.status == OrderStatus.VIRTUAL_FILLED) {
            val existing = executionDao.findByOrderId(order.id)
            return PaperTradeResult(
                action = PaperTradeAction.ALREADY_FILLED,
                orderId = order.id,
                executionId = existing?.id,
                message = "order already filled",
            )
        }
        require(order.side == OrderSide.BUY)
        require(quantity > 0L)
        require(executionPriceWon > 0L)
        val gross = executionPriceWon * quantity
        val totalOutflow = gross + commissionWon
        val cash = cashLedger.currentCash(order.strategyRunId)
        require(totalOutflow <= cash) { "insufficient cash for buy fill" }

        return database.withTransaction {
            val marketInstant = MarketExecutionTime.of(executionDate)
            val executionId = executionDao.insert(
                ExecutionEntity(
                    orderId = order.id,
                    executionPrice = executionPriceWon,
                    quantity = quantity,
                    commission = commissionWon,
                    tax = 0L,
                    slippage = 0L,
                    executedAt = marketInstant,
                    createdAt = now(),
                ),
            )
            if (failAfterExecution) {
                error("forced fill failure after execution")
            }
            cashLedger.append(
                strategyRunId = order.strategyRunId,
                eventType = CashLedgerEventType.BUY,
                amountWon = -gross,
                eventDate = executionDate,
                referenceType = CashLedgerReferenceTypes.EXECUTION,
                referenceId = executionId,
            )
            if (commissionWon > 0L) {
                cashLedger.append(
                    strategyRunId = order.strategyRunId,
                    eventType = CashLedgerEventType.COMMISSION,
                    amountWon = -commissionWon,
                    eventDate = executionDate,
                    referenceType = CashLedgerReferenceTypes.EXECUTION,
                    referenceId = executionId,
                )
            }
            val existing = positionDao.find(order.strategyRunId, order.instrumentId)
            if (existing == null) {
                positionDao.insert(
                    PositionEntity(
                        strategyRunId = order.strategyRunId,
                        instrumentId = order.instrumentId,
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
            orderDao.update(
                order.copy(
                    quantity = quantity,
                    status = OrderStatus.VIRTUAL_FILLED,
                    executedAt = marketInstant,
                ),
            )
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
        if (order.status == OrderStatus.VIRTUAL_FILLED) {
            val existing = executionDao.findByOrderId(order.id)
            return PaperTradeResult(
                action = PaperTradeAction.ALREADY_FILLED,
                orderId = order.id,
                executionId = existing?.id,
                message = "order already filled",
            )
        }
        require(order.side == OrderSide.SELL)
        require(quantity > 0L)
        val position = positionDao.find(order.strategyRunId, order.instrumentId)
            ?: error("no position for sell")
        require(position.quantity == quantity) { "Phase 6 sells full position only" }
        val gross = executionPriceWon * quantity
        val buyCostBasis = position.averagePrice * quantity
        val netInflow = gross - commissionWon - taxWon
        val realized = netInflow - buyCostBasis

        return database.withTransaction {
            val marketInstant = MarketExecutionTime.of(executionDate)
            val executionId = executionDao.insert(
                ExecutionEntity(
                    orderId = order.id,
                    executionPrice = executionPriceWon,
                    quantity = quantity,
                    commission = commissionWon,
                    tax = taxWon,
                    slippage = 0L,
                    executedAt = marketInstant,
                    createdAt = now(),
                ),
            )
            if (failAfterExecution) {
                error("forced fill failure after execution")
            }
            cashLedger.append(
                strategyRunId = order.strategyRunId,
                eventType = CashLedgerEventType.SELL,
                amountWon = gross,
                eventDate = executionDate,
                referenceType = CashLedgerReferenceTypes.EXECUTION,
                referenceId = executionId,
            )
            if (commissionWon > 0L) {
                cashLedger.append(
                    strategyRunId = order.strategyRunId,
                    eventType = CashLedgerEventType.COMMISSION,
                    amountWon = -commissionWon,
                    eventDate = executionDate,
                    referenceType = CashLedgerReferenceTypes.EXECUTION,
                    referenceId = executionId,
                )
            }
            if (taxWon > 0L) {
                cashLedger.append(
                    strategyRunId = order.strategyRunId,
                    eventType = CashLedgerEventType.TAX,
                    amountWon = -taxWon,
                    eventDate = executionDate,
                    referenceType = CashLedgerReferenceTypes.EXECUTION,
                    referenceId = executionId,
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
            orderDao.update(
                order.copy(
                    quantity = quantity,
                    status = OrderStatus.VIRTUAL_FILLED,
                    executedAt = marketInstant,
                ),
            )
            PaperTradeResult(
                action = PaperTradeAction.FILLED,
                orderId = order.id,
                executionId = executionId,
            )
        }
    }
}
