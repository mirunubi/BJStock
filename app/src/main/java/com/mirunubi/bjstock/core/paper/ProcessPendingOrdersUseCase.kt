package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.RunStatus

class ProcessPendingOrdersUseCase(
    private val strategyRunDao: StrategyRunDao,
    private val orderDao: OrderDao,
    private val evaluationDao: StockEvaluationDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val positionDao: PositionDao,
    private val cashLedger: CashLedgerService,
    private val fills: VirtualFillService,
    private val policyService: PaperTradingPolicyService,
) {
    suspend operator fun invoke(
        strategyRunId: Long,
        asOfMarketDate: java.time.LocalDate? = null,
    ): List<PaperTradeResult> {
        val run = strategyRunDao.findById(strategyRunId)
            ?: return listOf(
                PaperTradeResult(PaperTradeAction.NO_TRADE, "strategy run not found"),
            )
        if (run.status !in setOf(RunStatus.READY, RunStatus.RUNNING)) {
            return listOf(
                PaperTradeResult(PaperTradeAction.NO_TRADE, "run status ${run.status}"),
            )
        }
        val policy = try {
            policyService.requireRuntime(strategyRunId)
        } catch (_: MissingTradingPolicyException) {
            return listOf(
                PaperTradeResult(
                    action = PaperTradeAction.MISSING_TRADING_POLICY,
                    message = "MISSING_TRADING_POLICY",
                ),
            )
        }
        val pending = orderDao.findByRunAndStatus(strategyRunId, OrderStatus.PENDING_EXECUTION)
            .sortedWith(compareBy({ it.evaluationId ?: Long.MAX_VALUE }, { it.id }))
        return pending.map { order -> processOne(order, policy, asOfMarketDate) }
    }

    suspend fun processOne(
        orderId: Long,
        asOfMarketDate: java.time.LocalDate? = null,
    ): PaperTradeResult {
        val order = orderDao.findById(orderId)
            ?: return PaperTradeResult(PaperTradeAction.NO_TRADE, "order not found")
        val policy = try {
            policyService.requireRuntime(order.strategyRunId)
        } catch (_: MissingTradingPolicyException) {
            return PaperTradeResult(
                action = PaperTradeAction.MISSING_TRADING_POLICY,
                orderId = order.id,
                message = "MISSING_TRADING_POLICY",
            )
        }
        return processOne(order, policy, asOfMarketDate)
    }

    private suspend fun processOne(
        order: OrderEntity,
        policy: RunPaperTradingPolicy,
        asOfMarketDate: java.time.LocalDate?,
    ): PaperTradeResult {
        if (order.status == OrderStatus.VIRTUAL_FILLED) {
            return PaperTradeResult(
                action = PaperTradeAction.ALREADY_FILLED,
                orderId = order.id,
            )
        }
        if (order.status == OrderStatus.REJECTED || order.status == OrderStatus.CANCELLED) {
            return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                orderId = order.id,
                message = "order status ${order.status}",
            )
        }
        if (order.status != OrderStatus.PENDING_EXECUTION && order.status != OrderStatus.CREATED) {
            return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                orderId = order.id,
                message = "order status ${order.status}",
            )
        }
        require(policy.executionPricePolicy == ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN)

        val signalDate = order.evaluationId?.let { evaluationDao.findEvaluationById(it)?.evaluationDate }
            ?: return PaperTradeResult(
                action = PaperTradeAction.NO_TRADE,
                orderId = order.id,
                message = "evaluation missing for pending order",
            )

        val nextBar = if (asOfMarketDate == null) {
            marketDailyBarDao.findNextTradingBar(order.instrumentId, signalDate)
        } else {
            marketDailyBarDao.findNextTradingBarAsOf(
                instrumentId = order.instrumentId,
                afterDate = signalDate,
                asOfDate = asOfMarketDate,
            )
        } ?: return PaperTradeResult(
            action = PaperTradeAction.PENDING,
            orderId = order.id,
            message = "waiting for next trading bar after $signalDate" +
                (asOfMarketDate?.let { " asOf $it" } ?: ""),
        )

        return when (order.side) {
            OrderSide.BUY -> fillBuy(order, nextBar.tradeDate, nextBar.openPrice, policy)
            OrderSide.SELL -> fillSell(order, nextBar.tradeDate, nextBar.openPrice, policy)
        }
    }

    private suspend fun fillBuy(
        order: OrderEntity,
        executionDate: java.time.LocalDate,
        openPrice: Long,
        policy: RunPaperTradingPolicy,
    ): PaperTradeResult {
        val price = policy.slippagePolicy.applyToBuy(openPrice)
        val cash = cashLedger.currentCash(order.strategyRunId)
        val budget = PaperQuantityMath.buyBudgetFromRate(cash, policy.buyAllocationRate)
        val quantity = PaperQuantityMath.maxAffordableBuyQuantity(
            cashBudgetWon = budget,
            executionPriceWon = price,
            costPolicy = policy.costPolicy,
        )
        if (quantity <= 0L) {
            orderDao.update(order.copy(status = OrderStatus.REJECTED, quantity = 0L))
            return PaperTradeResult(
                action = PaperTradeAction.ORDER_REJECTED,
                orderId = order.id,
                message = "INSUFFICIENT_CASH",
            )
        }
        val gross = price * quantity
        val commission = policy.costPolicy.commission(gross)
        return fills.executeBuy(
            order = order,
            executionDate = executionDate,
            executionPriceWon = price,
            quantity = quantity,
            commissionWon = commission,
        )
    }

    private suspend fun fillSell(
        order: OrderEntity,
        executionDate: java.time.LocalDate,
        openPrice: Long,
        policy: RunPaperTradingPolicy,
    ): PaperTradeResult {
        val position = positionDao.find(order.strategyRunId, order.instrumentId)
        val quantity = position?.quantity ?: 0L
        if (quantity <= 0L) {
            orderDao.update(order.copy(status = OrderStatus.REJECTED, quantity = 0L))
            return PaperTradeResult(
                action = PaperTradeAction.ORDER_REJECTED,
                orderId = order.id,
                message = "no position at fill time",
            )
        }
        val price = policy.slippagePolicy.applyToSell(openPrice)
        val gross = price * quantity
        val commission = policy.costPolicy.commission(gross)
        val tax = policy.costPolicy.sellTax(gross)
        return fills.executeSell(
            order = order,
            executionDate = executionDate,
            executionPriceWon = price,
            quantity = quantity,
            commissionWon = commission,
            taxWon = tax,
        )
    }
}
