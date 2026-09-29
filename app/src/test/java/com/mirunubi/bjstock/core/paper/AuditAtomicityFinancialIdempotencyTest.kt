package com.mirunubi.bjstock.core.paper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.audit.ForwardOperationContext
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.CashLedgerDao
import com.mirunubi.bjstock.core.database.BJStockMigrations
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.dao.TradeAuditLogDao
import com.mirunubi.bjstock.core.database.entity.CashLedgerEntity
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationDetailEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.ErrorSeverity
import com.mirunubi.bjstock.core.error.IntegrityViolationException
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import com.mirunubi.bjstock.core.model.CashLedgerReferenceTypes
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.strategy.EvaluateStrategyRunUseCase
import com.mirunubi.bjstock.core.strategy.SignalRuleEngine
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationLoader
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationRepository
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 11 / Gate 6: audit atomicity and financial idempotency
 * (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §11–§12). Failures are injected at the DAO boundary.
 */
@RunWith(RobolectricTestRunner::class)
class AuditAtomicityFinancialIdempotencyTest {
    private lateinit var database: BJStockDatabase
    private lateinit var strategyService: StrategyVersionService
    private lateinit var runService: StrategyRunService
    private lateinit var policyService: PaperTradingPolicyService
    private lateinit var factorValues: FactorValueRepository
    private var instrumentId = 0L
    private var factorId = 0L
    private var runId = 0L
    private val prevDay = LocalDate.of(2026, 9, 17)
    private val friday = LocalDate.of(2026, 9, 18)
    private val monday = LocalDate.of(2026, 9, 21)
    private val tuesday = LocalDate.of(2026, 9, 22)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        factorValues.ensureSystemFactorDefinitions()
        factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id
        strategyService = StrategyVersionService(
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = SystemFactorRegistryFactory.create(),
            signalRuleDao = database.strategySignalRuleDao(),
            now = { Instant.EPOCH },
        )
        policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH }
        runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH },
            policyService = policyService,
            factorRegistry = SystemFactorRegistryFactory.create(),
            defaultPolicyTemplate = { PaperTradingPolicy.DEFAULT },
            now = { Instant.EPOCH },
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "000660", name = "SK Hynix"),
        )
        insertBar(prevDay, open = 100, close = 100)
        insertBar(friday, open = 103, close = 103)
        runId = newRun(createActiveFactorOnly(), 100_000_000L)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // 1
    @Test
    fun evaluationInsertFailure_leavesNoPartialState() = runBlocking<Unit> {
        insertFactorValue(friday)
        val harness = Harness(evaluationDao = FailingDetailDao(database.stockEvaluationDao()))
        val failure = runCatching { harness.evaluateRun(runId, instrumentId, friday) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, database.stockEvaluationDao().countEvaluations())
        assertEquals(0, database.stockEvaluationDao().countDetails())
        assertEquals(0, database.tradeAuditLogDao().countByRun(runId))
    }

    // 2
    @Test
    fun evaluationAuditFailure_rollsBackEvaluationAndDetails() = runBlocking<Unit> {
        insertFactorValue(friday)
        val harness = Harness(auditDao = FailingAuditDao(database.tradeAuditLogDao(), TradeAuditEventType.EVALUATION_DECIDED))
        assertNotNull(runCatching { harness.evaluateRun(runId, instrumentId, friday) }.exceptionOrNull())
        assertEquals(0, database.stockEvaluationDao().countEvaluations())
        assertEquals(0, database.stockEvaluationDao().countDetails())
        assertEquals(0, database.tradeAuditLogDao().countByRun(runId))

        val ok = Harness().evaluateRun(runId, instrumentId, friday)
        val evaluationId = ok.persistedEvaluationId!!
        val decided = auditByKey(TradeAuditLogService.evaluationDecisionKey(evaluationId))!!
        assertEquals(DecisionSource.FACTOR_STRATEGY, decided.decisionSource)
    }

    @Test
    fun ruleTriggeredAuditFailure_rollsBackRuleEvaluation() = runBlocking<Unit> {
        val ruleRun = newRun(createActiveWithSellRule(), 100_000_000L)
        val harness = Harness(auditDao = FailingAuditDao(database.tradeAuditLogDao(), TradeAuditEventType.RULE_TRIGGERED))
        assertNotNull(runCatching { harness.evaluateRun(ruleRun, instrumentId, friday) }.exceptionOrNull())
        assertEquals(0, database.stockEvaluationDao().countEvaluations())
        assertEquals(0, database.tradeAuditLogDao().countByRun(ruleRun))

        val ok = Harness().evaluateRun(ruleRun, instrumentId, friday)
        assertEquals(DecisionSource.SIGNAL_RULE, ok.decisionSource)
        val types = database.tradeAuditLogDao().findByRun(ruleRun).map { it.eventType }
        assertEquals(listOf(TradeAuditEventType.RULE_TRIGGERED, TradeAuditEventType.EVALUATION_DECIDED), types)
    }

    // 3
    @Test
    fun orderAuditFailure_rollsBackOrder() = runBlocking<Unit> {
        val evaluationId = insertEvaluation(friday, TradeDecision.BUY)
        val harness = Harness(auditDao = FailingAuditDao(database.tradeAuditLogDao(), TradeAuditEventType.ORDER_CREATED))
        assertNotNull(runCatching { harness.processEvaluation(evaluationId) }.exceptionOrNull())
        assertEquals(0, database.orderDao().countByRun(runId))
        assertEquals(0, database.tradeAuditLogDao().countByRun(runId))

        assertEquals(PaperTradeAction.ORDER_CREATED, Harness().processEvaluation(evaluationId).action)
        assertEquals(1, database.orderDao().countByRun(runId))
    }

    // 4
    @Test
    fun executionAuditFailure_rollsBackExecutionLedgerPositionAndOrderTransition() = runBlocking<Unit> {
        createPendingBuy()
        val harness = Harness(auditDao = FailingAuditDao(database.tradeAuditLogDao(), TradeAuditEventType.EXECUTION_FILLED))
        assertNotNull(runCatching { harness.processPending(runId) }.exceptionOrNull())
        assertUntouchedPendingBuy()
    }

    // 5
    @Test
    fun ledgerInsertFailure_rollsBackExecutionTransaction() = runBlocking<Unit> {
        createPendingBuy()
        val harness = Harness(ledgerDao = FailingLedgerDao(database.cashLedgerDao(), CashLedgerEventType.COMMISSION))
        assertNotNull(runCatching { harness.processPending(runId) }.exceptionOrNull())
        assertUntouchedPendingBuy()
    }

    // 6
    @Test
    fun duplicateExecutionKey_neverCausesSecondFinancialMutation() = runBlocking<Unit> {
        val order = createPendingBuy()
        val harness = Harness()
        val fill = harness.processPending(runId).single()
        assertEquals(PaperTradeAction.FILLED, fill.action)
        val execution = database.executionDao().findById(fill.executionId!!)!!
        val cashAfter = harness.cash.currentCash(runId)

        val dbDuplicate = runCatching { database.executionDao().insert(execution.copy(id = 0)) }
        assertTrue(dbDuplicate.isFailure)

        val mismatch = runCatching {
            harness.fills.executeBuy(order, monday, execution.executionPrice, execution.quantity - 1, execution.commission)
        }.exceptionOrNull() as IntegrityViolationException
        assertEquals("EXECUTION_REPLAY_MISMATCH", mismatch.reasonCode)
        assertEquals(AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT, mismatch.code)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, mismatch.severity)
        val mapped = AppErrorMapper.fromThrowable(mismatch)
        assertEquals(AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT, mapped.code)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, mapped.severity)

        assertEquals(1, database.executionDao().countByRun(runId))
        assertEquals(3, database.cashLedgerDao().countByRun(runId))
        assertEquals(cashAfter, harness.cash.currentCash(runId))
    }

    @Test
    fun orphanExecutionUnderKey_forPendingOrder_abortsWithoutMutation() = runBlocking<Unit> {
        val order = createPendingBuy()
        database.executionDao().insert(
            ExecutionEntity(
                orderId = order.id,
                executionPrice = 50_000,
                quantity = 1,
                executedAt = MarketExecutionTime.of(monday),
                createdAt = Instant.EPOCH,
                executionKey = VirtualFillService.executionKey(order.id),
            ),
        )
        val failure = runCatching { Harness().processPending(runId) }.exceptionOrNull() as IntegrityViolationException
        assertEquals("EXECUTION_ORDER_STATE_MISMATCH", failure.reasonCode)
        assertEquals(AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT, failure.code)
        assertEquals(1, database.cashLedgerDao().countByRun(runId))
        assertNull(database.positionDao().find(runId, instrumentId))
        assertEquals(OrderStatus.PENDING_EXECUTION, database.orderDao().findById(order.id)!!.status)
    }

    @Test
    fun filledOrderWithoutExecution_abortsWithItsOwnInvariantCode_andMutatesNothing() = runBlocking<Unit> {
        val order = createPendingBuy()
        database.orderDao().update(database.orderDao().findById(order.id)!!.copy(status = OrderStatus.VIRTUAL_FILLED))
        val auditsBefore = database.tradeAuditLogDao().countByRun(runId)

        val failure = runCatching {
            Harness().fills.executeBuy(order, monday, 50_000, 1, 0)
        }.exceptionOrNull() as IntegrityViolationException
        assertEquals(AppErrorCode.FILLED_ORDER_WITHOUT_EXECUTION, failure.code)
        assertEquals("FILLED_ORDER_WITHOUT_EXECUTION", failure.reasonCode)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, failure.severity)
        val mapped = AppErrorMapper.fromThrowable(failure)
        assertEquals(AppErrorCode.FILLED_ORDER_WITHOUT_EXECUTION, mapped.code)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, mapped.severity)

        assertEquals(0, database.executionDao().countByOrderId(order.id))
        assertEquals(1, database.cashLedgerDao().countByRun(runId))
        assertNull(database.positionDao().find(runId, instrumentId))
        assertEquals(OrderStatus.VIRTUAL_FILLED, database.orderDao().findById(order.id)!!.status)
        assertEquals(auditsBefore, database.tradeAuditLogDao().countByRun(runId))
    }

    // 7
    @Test
    fun duplicateLedgerEventKey_neverCausesSecondCashMutation() = runBlocking<Unit> {
        val cash = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        val before = cash.currentCash(runId)
        suspend fun adjust(amount: Long) = cash.append(
            strategyRunId = runId,
            eventType = CashLedgerEventType.ADJUSTMENT,
            amountWon = amount,
            eventDate = monday,
            referenceType = CashLedgerReferenceTypes.STRATEGY_RUN,
            referenceId = runId,
            eventKey = "run:$runId:test-adjustment",
        )
        val first = adjust(-1_000)
        val replay = adjust(-1_000)
        assertEquals(first.id, replay.id)
        assertEquals(before - 1_000, cash.currentCash(runId))
        assertEquals(2, database.cashLedgerDao().countByRun(runId))

        val conflict = runCatching { adjust(-2_000) }.exceptionOrNull() as IntegrityViolationException
        assertEquals("LEDGER_EVENT_KEY_CONFLICT", conflict.reasonCode)
        assertEquals(AppErrorCode.LEDGER_MISMATCH, conflict.code)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, conflict.severity)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, AppErrorMapper.fromThrowable(conflict).severity)
        assertEquals(before - 1_000, cash.currentCash(runId))
        assertEquals(2, database.cashLedgerDao().countByRun(runId))
        assertEquals(before - 1_000, cash.reconstructCash(runId))
    }

    // 8, 9, 10
    @Test
    fun replayingSameFill_yieldsOneExecution_andIdenticalCashAndPosition() = runBlocking<Unit> {
        val staleOrder = createPendingBuy()
        val harness = Harness()
        val fill = harness.processPending(runId).single()
        val execution = database.executionDao().findById(fill.executionId!!)!!
        val cashAfter = harness.cash.currentCash(runId)
        val ledgerAfter = database.cashLedgerDao().findByRun(runId)
        val positionAfter = database.positionDao().find(runId, instrumentId)!!

        repeat(3) {
            val replay = harness.fills.executeBuy(
                order = staleOrder,
                executionDate = monday,
                executionPriceWon = execution.executionPrice,
                quantity = execution.quantity,
                commissionWon = execution.commission,
            )
            assertEquals(PaperTradeAction.ALREADY_FILLED, replay.action)
            assertEquals(execution.id, replay.executionId)
        }
        repeat(3) { harness.processPending(runId) }

        assertEquals(1, database.executionDao().countByOrderId(staleOrder.id))
        assertEquals(cashAfter, harness.cash.currentCash(runId))
        assertEquals(ledgerAfter, database.cashLedgerDao().findByRun(runId))
        assertEquals(positionAfter, database.positionDao().find(runId, instrumentId))
        assertEquals(1, auditCount(TradeAuditEventType.EXECUTION_FILLED))
    }

    @Test
    fun replayedFill_restoresMissingLegacyExecutionFilledAudit() = runBlocking<Unit> {
        val staleOrder = createPendingBuy()
        val harness = Harness()
        val execution = database.executionDao().findById(harness.processPending(runId).single().executionId!!)!!
        deleteAudit(TradeAuditLogService.executionFilledKey(execution.id))

        withContext(ForwardOperationContext(501)) {
            harness.fills.executeBuy(staleOrder, monday, execution.executionPrice, execution.quantity, execution.commission)
        }
        val restored = auditByKey(TradeAuditLogService.executionFilledKey(execution.id))!!
        assertEquals(TradeAuditLogService.RECONCILED_REASON_CODE, restored.reasonCode)
        assertEquals(execution.id, restored.executionId)
        assertNull(restored.operationId)
        assertEquals(1, database.executionDao().countByRun(runId))
    }

    // 11
    @Test
    fun replayingAuditEventKey_yieldsOneRow_andConflictAborts() = runBlocking<Unit> {
        val audit = TradeAuditLogService(database.tradeAuditLogDao()) { Instant.EPOCH }
        val key = TradeAuditLogService.orderRejectedKey(77)
        val first = audit.append(runId, TradeAuditEventType.ORDER_REJECTED, key, orderId = 77, reasonCode = "INSUFFICIENT_CASH")
        val replay = audit.append(runId, TradeAuditEventType.ORDER_REJECTED, key, orderId = 77, reasonCode = "INSUFFICIENT_CASH")
        assertEquals(first, replay)
        assertEquals(1, database.tradeAuditLogDao().countByRun(runId))

        val conflict = runCatching {
            audit.append(runId, TradeAuditEventType.ORDER_CANCELLED, key, orderId = 77)
        }.exceptionOrNull() as IntegrityViolationException
        assertEquals("AUDIT_EVENT_KEY_CONFLICT", conflict.reasonCode)
        assertEquals(AppErrorCode.INTERNAL_INVARIANT_VIOLATION, conflict.code)
        assertEquals(1, database.tradeAuditLogDao().countByRun(runId))
    }

    // 12
    @Test
    fun missingLegacyEvaluationDecided_isRestoredWithoutChangingEvaluation() = runBlocking<Unit> {
        insertFactorValue(friday)
        val harness = Harness()
        val evaluationId = harness.evaluateRun(runId, instrumentId, friday).persistedEvaluationId!!
        deleteAudit(TradeAuditLogService.evaluationDecisionKey(evaluationId))
        val evaluationBefore = database.stockEvaluationDao().findEvaluationById(evaluationId)
        val detailsBefore = database.stockEvaluationDao().findDetails(evaluationId)

        val replay = withContext(ForwardOperationContext(502)) { harness.evaluateRun(runId, instrumentId, friday) }
        assertEquals(StrategyEvaluationStatus.ALREADY_EVALUATED, replay.status)
        val restored = auditByKey(TradeAuditLogService.evaluationDecisionKey(evaluationId))!!
        assertEquals(TradeAuditEventType.EVALUATION_DECIDED, restored.eventType)
        assertEquals(TradeAuditLogService.RECONCILED_REASON_CODE, restored.reasonCode)
        assertEquals(DecisionSource.FACTOR_STRATEGY, restored.decisionSource)
        assertEquals(evaluationId, restored.evaluationId)
        assertNull(restored.operationId)
        assertEquals(evaluationBefore, database.stockEvaluationDao().findEvaluationById(evaluationId))
        assertEquals(detailsBefore, database.stockEvaluationDao().findDetails(evaluationId))
        assertEquals(1, database.stockEvaluationDao().countEvaluations())

        harness.evaluateRun(runId, instrumentId, friday)
        assertEquals(1, auditCount(TradeAuditEventType.EVALUATION_DECIDED))
    }

    // 13
    @Test
    fun missingLegacyOrderCreated_isRestoredWithoutDuplicateOrder() = runBlocking<Unit> {
        val order = createPendingBuy()
        deleteAudit(TradeAuditLogService.orderCreatedKey(order.id))

        val replay = withContext(ForwardOperationContext(503)) { Harness().processEvaluation(order.evaluationId!!) }
        assertEquals(PaperTradeAction.ORDER_ALREADY_EXISTS, replay.action)
        assertEquals(1, database.orderDao().countByRun(runId))
        assertEquals(order, database.orderDao().findById(order.id))
        val restored = auditByKey(TradeAuditLogService.orderCreatedKey(order.id))!!
        assertEquals(TradeAuditLogService.RECONCILED_REASON_CODE, restored.reasonCode)
        assertEquals(order.id, restored.orderId)
        assertNull(restored.operationId)
    }

    // 14
    @Test
    fun orderRejected_isEmittedAtomicallyWithCanonicalReason() = runBlocking<Unit> {
        val poorRun = newRun(createActiveFactorOnly(), 10_000L)
        val buyEvaluation = insertEvaluation(friday, TradeDecision.BUY, run = poorRun)
        insertBar(monday, open = 50_000, close = 50_000)
        Harness().processEvaluation(buyEvaluation)
        val order = database.orderDao().findByRun(poorRun).single()

        val failing = Harness(auditDao = FailingAuditDao(database.tradeAuditLogDao(), TradeAuditEventType.ORDER_REJECTED))
        assertNotNull(runCatching { failing.processPending(poorRun) }.exceptionOrNull())
        assertEquals(OrderStatus.PENDING_EXECUTION, database.orderDao().findById(order.id)!!.status)

        val rejected = withContext(ForwardOperationContext(504)) { Harness().processPending(poorRun) }.single()
        assertEquals(PaperTradeAction.ORDER_REJECTED, rejected.action)
        assertEquals("INSUFFICIENT_CASH", rejected.message)
        assertEquals(OrderStatus.REJECTED, database.orderDao().findById(order.id)!!.status)
        val log = auditByKey(TradeAuditLogService.orderRejectedKey(order.id))!!
        assertEquals(TradeAuditEventType.ORDER_REJECTED, log.eventType)
        assertEquals(AppErrorCode.INSUFFICIENT_CASH.name, log.reasonCode)
        assertEquals(monday, log.marketDate)
        assertEquals(instrumentId, log.instrumentId)
        assertEquals(504L, log.operationId)
        assertEquals(0, database.executionDao().countByOrderId(order.id))
        assertEquals(10_000L, CashLedgerService(database.cashLedgerDao()).currentCash(poorRun))
    }

    @Test
    fun sellRejectedAtFillTime_emitsNoPositionReason() = runBlocking<Unit> {
        val position = seedOpenPosition(quantity = 10)
        val sellEvaluation = insertEvaluation(monday, TradeDecision.SELL)
        Harness().processEvaluation(sellEvaluation)
        database.positionDao().update(position.copy(quantity = 0L))
        insertBar(tuesday, open = 50_000, close = 50_000)
        val order = database.orderDao().findByRun(runId).single { it.side == OrderSide.SELL }

        val result = Harness().processPending(runId).single { it.orderId == order.id }
        assertEquals(PaperTradeAction.ORDER_REJECTED, result.action)
        assertEquals("no position at fill time", result.message)
        val log = auditByKey(TradeAuditLogService.orderRejectedKey(order.id))!!
        assertEquals(AppErrorCode.NO_POSITION_TO_SELL.name, log.reasonCode)
    }

    // 15
    @Test
    fun orderCancelled_isEmittedAtomicallyAtRunEnd() = runBlocking<Unit> {
        val order = createPendingBuy(withBar = false)
        val failing = Harness(auditDao = FailingAuditDao(database.tradeAuditLogDao(), TradeAuditEventType.ORDER_CANCELLED))
        assertNotNull(runCatching { failing.processPending.finalizeRunEnd(runId, friday, Instant.EPOCH) }.exceptionOrNull())
        assertEquals(OrderStatus.PENDING_EXECUTION, database.orderDao().findById(order.id)!!.status)

        withContext(ForwardOperationContext(505)) {
            Harness().processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(9))
        }
        val cancelled = database.orderDao().findById(order.id)!!
        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        assertEquals(Instant.ofEpochSecond(9), cancelled.cancelledAt)
        val log = auditByKey(TradeAuditLogService.orderCancelledKey(order.id))!!
        assertEquals(TradeAuditEventType.ORDER_CANCELLED, log.eventType)
        assertEquals(ProcessPendingOrdersUseCase.RUN_END_REACHED, log.reasonCode)
        assertEquals(runId, log.strategyRunId)
        assertEquals(instrumentId, log.instrumentId)
        assertEquals(order.id, log.orderId)
        assertEquals(friday, log.marketDate)
        assertEquals(505L, log.operationId)
        assertEquals(RunStatus.COMPLETED, database.strategyRunDao().findById(runId)!!.status)

        Harness().processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(10))
        assertEquals(1, auditCount(TradeAuditEventType.ORDER_CANCELLED))
        assertEquals(0, database.executionDao().countByOrderId(order.id))
    }

    // 16, 17
    @Test
    fun operationIdRetainedOnNewAudit_andHistoricalNullRemainsValid() = runBlocking<Unit> {
        val evaluationId = insertEvaluation(friday, TradeDecision.BUY)
        insertBar(monday, open = 50_000, close = 50_000)
        val harness = Harness()
        withContext(ForwardOperationContext(601)) {
            harness.processEvaluation(evaluationId)
            harness.processPending(runId)
        }
        val order = database.orderDao().findByRun(runId).single()
        val execution = database.executionDao().findByOrderId(order.id)!!
        assertEquals(601L, auditByKey(TradeAuditLogService.orderCreatedKey(order.id))!!.operationId)
        assertEquals(601L, auditByKey(TradeAuditLogService.executionFilledKey(execution.id))!!.operationId)

        val legacyId = harness.audit.append(
            strategyRunId = runId,
            eventType = TradeAuditEventType.ORDER_SKIPPED,
            eventKey = TradeAuditLogService.orderSkippedKey(9_999, "POSITION_ALREADY_OPEN"),
        )
        assertNull(database.tradeAuditLogDao().findByRun(runId).single { it.id == legacyId }.operationId)
        assertEquals(
            legacyId,
            harness.audit.append(
                strategyRunId = runId,
                eventType = TradeAuditEventType.ORDER_SKIPPED,
                eventKey = TradeAuditLogService.orderSkippedKey(9_999, "POSITION_ALREADY_OPEN"),
            ),
        )
    }

    @Test
    fun financialInvariants_holdAcrossBuyAndSell() = runBlocking<Unit> {
        val harness = Harness()
        val policy = policyService.requireRuntime(runId)
        val buyOrder = createPendingBuy()
        val buyExecution = database.executionDao().findById(harness.processPending(runId).single().executionId!!)!!
        val afterBuy = database.positionDao().find(runId, instrumentId)!!
        assertEquals(buyExecution.quantity, afterBuy.quantity)
        assertEquals(policy.slippagePolicy.applyToBuy(50_000), buyExecution.executionPrice)

        val sellEvaluation = insertEvaluation(monday, TradeDecision.SELL)
        harness.processEvaluation(sellEvaluation)
        insertBar(tuesday, open = 55_000, close = 55_000)
        val sellExecution = database.executionDao().findById(
            harness.processPending(runId).single().executionId!!,
        )!!
        assertEquals(policy.slippagePolicy.applyToSell(55_000), sellExecution.executionPrice)
        assertEquals(buyExecution.quantity, sellExecution.quantity)
        assertEquals(0L, database.positionDao().find(runId, instrumentId)!!.quantity)
        assertTrue(database.positionDao().findOpenByRun(runId).all { it.quantity >= 0L })

        val ledger = database.cashLedgerDao().findByRun(runId)
        var running = 0L
        ledger.forEach { row ->
            assertEquals("cash_before + delta = cash_after at ${row.eventKey}", running + row.amount, row.balanceAfter)
            running = row.balanceAfter
        }
        assertEquals(running, harness.cash.reconstructCash(runId))

        assertLedgerGroup(
            ledger, buyExecution,
            mapOf(
                CashLedgerService.buyPrincipalKey(buyExecution.id) to -(buyExecution.executionPrice * buyExecution.quantity),
                CashLedgerService.buyCommissionKey(buyExecution.id) to -buyExecution.commission,
            ),
        )
        assertLedgerGroup(
            ledger, sellExecution,
            mapOf(
                CashLedgerService.sellProceedsKey(sellExecution.id) to sellExecution.executionPrice * sellExecution.quantity,
                CashLedgerService.sellCommissionKey(sellExecution.id) to -sellExecution.commission,
                CashLedgerService.sellTaxKey(sellExecution.id) to -sellExecution.tax,
            ),
        )

        database.orderDao().findByRun(runId).forEach { order ->
            val executions = database.executionDao().countByOrderId(order.id)
            when (order.status) {
                OrderStatus.VIRTUAL_FILLED -> {
                    assertEquals(1, executions)
                    val execution = database.executionDao().findByExecutionKey(VirtualFillService.executionKey(order.id))!!
                    assertEquals(order.quantity, execution.quantity)
                    assertEquals(order.executedAt, execution.executedAt)
                }
                else -> assertEquals(0, executions)
            }
        }
        assertEquals(OrderStatus.VIRTUAL_FILLED, database.orderDao().findById(buyOrder.id)!!.status)
    }

    // Gate 6.1 — ledger arithmetic
    @Test
    fun ledgerArithmeticMismatch_abortsWithLedgerMismatch_andAppendsNothing() = runBlocking<Unit> {
        val cash = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        database.cashLedgerDao().insert(
            CashLedgerEntity(
                strategyRunId = runId,
                eventType = CashLedgerEventType.ADJUSTMENT,
                amount = -1_000,
                balanceAfter = 100_000_000L,
                referenceType = CashLedgerReferenceTypes.STRATEGY_RUN,
                referenceId = runId,
                eventDate = friday,
                createdAt = Instant.EPOCH,
                eventKey = "run:$runId:corrupt-adjustment",
            ),
        )
        val rowsBefore = database.cashLedgerDao().countByRun(runId)

        val append = runCatching {
            cash.append(
                strategyRunId = runId,
                eventType = CashLedgerEventType.ADJUSTMENT,
                amountWon = -500,
                eventDate = monday,
                referenceType = CashLedgerReferenceTypes.STRATEGY_RUN,
                referenceId = runId,
                eventKey = "run:$runId:next-adjustment",
            )
        }.exceptionOrNull() as IntegrityViolationException
        assertEquals(AppErrorCode.LEDGER_MISMATCH, append.code)
        assertEquals("LEDGER_BALANCE_MISMATCH", append.reasonCode)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, AppErrorMapper.fromThrowable(append).severity)
        assertEquals(rowsBefore, database.cashLedgerDao().countByRun(runId))

        val rebuild = runCatching { cash.reconstructCash(runId) }.exceptionOrNull() as IntegrityViolationException
        assertEquals(AppErrorCode.LEDGER_MISMATCH, rebuild.code)
        assertEquals("LEDGER_RECONSTRUCTION_MISMATCH", rebuild.reasonCode)
    }

    // Gate 6.1 — finalizeRunEnd atomic boundary
    @Test
    fun finalizeRunEnd_commitsAllCancellationsAuditsAndCompletion_andReplayAddsNothing() = runBlocking<Unit> {
        val orders = seedPendingOrders(3)
        withContext(ForwardOperationContext(701)) {
            Harness().processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(30))
        }
        assertFinalized(orders, Instant.ofEpochSecond(30))
        assertTrue(orders.all { auditByKey(TradeAuditLogService.orderCancelledKey(it.id))!!.operationId == 701L })

        Harness().processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(31))
        assertFinalized(orders, Instant.ofEpochSecond(30))
    }

    @Test
    fun finalizeRunEnd_anyFailure_rollsBackEverything_thenRetrySucceedsOnce() = runBlocking<Unit> {
        val orders = seedPendingOrders(3)
        val runBefore = database.strategyRunDao().findById(runId)
        fun cancelAuditFailure(at: Int) =
            Harness(auditDao = NthAuditFailureDao(database.tradeAuditLogDao(), TradeAuditEventType.ORDER_CANCELLED, at))
        listOf(
            "first cancellation audit" to cancelAuditFailure(1),
            "middle cancellation audit" to cancelAuditFailure(2),
            "last cancellation audit" to cancelAuditFailure(3),
            "run COMPLETED update" to Harness(runDao = FailingCompletionRunDao(database.strategyRunDao())),
        ).forEach { (case, harness) ->
            val failure = runCatching { harness.processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(40)) }
            assertNotNull(case, failure.exceptionOrNull())
            orders.forEach { assertEquals(case, it, database.orderDao().findById(it.id)) }
            assertEquals(case, 0, auditCount(TradeAuditEventType.ORDER_CANCELLED))
            assertEquals(case, runBefore, database.strategyRunDao().findById(runId))
        }

        Harness().processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(41))
        assertFinalized(orders, Instant.ofEpochSecond(41))
        Harness().processPending.finalizeRunEnd(runId, friday, Instant.ofEpochSecond(42))
        assertFinalized(orders, Instant.ofEpochSecond(41))
    }

    // Gate 6.1 — legacy terminal-order audit reconciliation
    @Test
    fun legacyTerminalOrders_getExactlyOneAudit_withProvenOrUnknownReason() = runBlocking<Unit> {
        val buyRejected = insertOrder("legacy-r-buy", OrderSide.BUY, OrderStatus.REJECTED, quantity = 0)
        val sellRejected = insertOrder("legacy-r-sell", OrderSide.SELL, OrderStatus.REJECTED, quantity = 0)
        val oddRejected = insertOrder("legacy-r-odd", OrderSide.BUY, OrderStatus.REJECTED, quantity = 7)
        val runEndCancelled = insertOrder(
            "legacy-c-end", OrderSide.BUY, OrderStatus.CANCELLED, quantity = 0, cancelledAt = Instant.ofEpochSecond(5),
        )
        val oddCancelled = insertOrder("legacy-c-odd", OrderSide.SELL, OrderStatus.CANCELLED, quantity = 3)
        val ordersBefore = database.orderDao().findByRun(runId)

        reconcileTerminalOrders()

        mapOf(
            TradeAuditLogService.orderRejectedKey(buyRejected.id) to AppErrorCode.INSUFFICIENT_CASH.name,
            TradeAuditLogService.orderRejectedKey(sellRejected.id) to AppErrorCode.NO_POSITION_TO_SELL.name,
            TradeAuditLogService.orderRejectedKey(oddRejected.id) to TradeAuditLogService.LEGACY_REASON_UNKNOWN,
            TradeAuditLogService.orderCancelledKey(runEndCancelled.id) to ProcessPendingOrdersUseCase.RUN_END_REACHED,
            TradeAuditLogService.orderCancelledKey(oddCancelled.id) to TradeAuditLogService.LEGACY_REASON_UNKNOWN,
        ).forEach { (key, reason) ->
            val log = auditByKey(key)!!
            assertEquals(key, reason, log.reasonCode)
            assertEquals(key, runId, log.strategyRunId)
            assertEquals(key, instrumentId, log.instrumentId)
            assertNull(key, log.operationId)
            assertNull(key, log.decisionSource)
            assertNull(key, log.marketDate)
            assertNull(key, log.executionId)
            assertTrue(key, log.reasonText!!.startsWith(TradeAuditLogService.RECONCILED_REASON_CODE))
        }
        assertEquals(3, auditCount(TradeAuditEventType.ORDER_REJECTED))
        assertEquals(2, auditCount(TradeAuditEventType.ORDER_CANCELLED))
        assertEquals(ordersBefore, database.orderDao().findByRun(runId))
        assertEquals(0L, emptyDecisionSourceCount())
    }

    @Test
    fun terminalReconciliation_isIdempotent_andNeverDuplicatesOrRewritesExistingAudit() = runBlocking<Unit> {
        val gate6 = insertOrder("gate6-pending", OrderSide.BUY, OrderStatus.PENDING_EXECUTION, quantity = 0)
        withContext(ForwardOperationContext(801)) {
            Harness().fills.reject(gate6, AppErrorCode.INSUFFICIENT_CASH.name, monday, "INSUFFICIENT_CASH")
        }
        val otherKey = insertOrder(
            "legacy-other-key", OrderSide.SELL, OrderStatus.CANCELLED, quantity = 0, cancelledAt = Instant.EPOCH,
        )
        Harness().audit.append(
            strategyRunId = runId,
            eventType = TradeAuditEventType.ORDER_CANCELLED,
            eventKey = "legacy:order:${otherKey.id}:cancelled",
            orderId = otherKey.id,
            reasonCode = ProcessPendingOrdersUseCase.RUN_END_REACHED,
        )
        insertOrder("legacy-missing", OrderSide.BUY, OrderStatus.REJECTED, quantity = 0)
        val before = database.tradeAuditLogDao().findByRun(runId)

        reconcileTerminalOrders()
        val afterFirst = database.tradeAuditLogDao().findByRun(runId)
        assertEquals(before.size + 1, afterFirst.size)
        assertEquals(before, afterFirst.filter { row -> before.any { it.id == row.id } })
        assertEquals(801L, auditByKey(TradeAuditLogService.orderRejectedKey(gate6.id))!!.operationId)
        assertNull(auditByKey(TradeAuditLogService.orderCancelledKey(otherKey.id)))

        reconcileTerminalOrders()
        assertEquals(afterFirst, database.tradeAuditLogDao().findByRun(runId))
    }

    // Gate 6.1 — legacy evaluation decision_source
    @Test
    fun restoredEvaluation_usesProvenSignalRuleSource() = runBlocking<Unit> {
        val ruleRun = newRun(createActiveWithSellRule(), 100_000_000L)
        val evaluationId = Harness().evaluateRun(ruleRun, instrumentId, friday).persistedEvaluationId!!
        deleteAudit(TradeAuditLogService.evaluationDecisionKey(evaluationId))

        Harness().evaluateRun(ruleRun, instrumentId, friday)
        val restored = auditByKey(TradeAuditLogService.evaluationDecisionKey(evaluationId))!!
        assertEquals(DecisionSource.SIGNAL_RULE, restored.decisionSource)
        assertEquals(TradeAuditLogService.RECONCILED_REASON_CODE, restored.reasonCode)
    }

    @Test
    fun restoredEvaluation_withoutProof_hasNullSource_neverEmptyString() = runBlocking<Unit> {
        val ruleRun = newRun(createActiveWithSellRule(), 100_000_000L)
        val evaluationId = Harness().evaluateRun(ruleRun, instrumentId, friday).persistedEvaluationId!!
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM trade_audit_logs WHERE evaluation_id = ?",
            arrayOf<Any>(evaluationId),
        )

        Harness().evaluateRun(ruleRun, instrumentId, friday)
        val restored = auditByKey(TradeAuditLogService.evaluationDecisionKey(evaluationId))!!
        assertNull(restored.decisionSource)
        assertEquals(TradeAuditLogService.RECONCILED_REASON_CODE, restored.reasonCode)
        assertNull(restored.operationId)
        assertEquals(0L, emptyDecisionSourceCount())
    }

    @Test
    fun liveEvaluationDecided_alwaysHasDecisionSource() = runBlocking<Unit> {
        insertFactorValue(friday)
        val ruleRun = newRun(createActiveWithSellRule(), 100_000_000L)
        assertEquals(DecisionSource.FACTOR_STRATEGY, Harness().evaluateRun(runId, instrumentId, friday).decisionSource)
        assertEquals(DecisionSource.SIGNAL_RULE, Harness().evaluateRun(ruleRun, instrumentId, friday).decisionSource)
        val decided = (database.tradeAuditLogDao().findByRun(runId) + database.tradeAuditLogDao().findByRun(ruleRun))
            .filter { it.eventType == TradeAuditEventType.EVALUATION_DECIDED }
        assertEquals(2, decided.size)
        assertEquals(
            listOf(DecisionSource.FACTOR_STRATEGY, DecisionSource.SIGNAL_RULE),
            decided.map { it.decisionSource },
        )
        assertTrue(decided.all { it.reasonCode != TradeAuditLogService.RECONCILED_REASON_CODE })
    }

    @Test
    fun existingLegacyNullSourceAudit_isNeverRewritten() = runBlocking<Unit> {
        insertFactorValue(friday)
        val evaluationId = Harness().evaluateRun(runId, instrumentId, friday).persistedEvaluationId!!
        val key = TradeAuditLogService.evaluationDecisionKey(evaluationId)
        deleteAudit(key)
        database.tradeAuditLogDao().insert(
            TradeAuditLogEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationId = evaluationId,
                marketDate = friday,
                eventType = TradeAuditEventType.EVALUATION_DECIDED,
                decisionSource = null,
                reasonCode = TradeAuditLogService.RECONCILED_REASON_CODE,
                reasonText = "BUY decision restored from persisted evaluation",
                eventKey = key,
                createdAt = Instant.EPOCH,
            ),
        )
        val legacy = auditByKey(key)

        Harness().evaluateRun(runId, instrumentId, friday)
        reconcileTerminalOrders()
        assertEquals(legacy, auditByKey(key))
        assertEquals(1, auditCount(TradeAuditEventType.EVALUATION_DECIDED))
    }

    private suspend fun seedPendingOrders(count: Int): List<OrderEntity> =
        (1..count).map { insertOrder("pending-$it", OrderSide.BUY, OrderStatus.PENDING_EXECUTION, quantity = 0) }

    private suspend fun insertOrder(
        tag: String,
        side: OrderSide,
        status: OrderStatus,
        quantity: Long,
        cancelledAt: Instant? = null,
    ): OrderEntity {
        val id = database.orderDao().insert(
            OrderEntity(
                clientOrderId = "paper-run-$runId-$tag-${side.name}",
                strategyRunId = runId,
                instrumentId = instrumentId,
                side = side,
                orderType = OrderType.MARKET,
                quantity = quantity,
                status = status,
                createdAt = Instant.EPOCH,
                cancelledAt = cancelledAt,
            ),
        )
        return database.orderDao().findById(id)!!
    }

    private suspend fun assertFinalized(orders: List<OrderEntity>, cancelledAt: Instant) {
        orders.forEach { order ->
            val current = database.orderDao().findById(order.id)!!
            assertEquals(OrderStatus.CANCELLED, current.status)
            assertEquals(cancelledAt, current.cancelledAt)
            val log = auditByKey(TradeAuditLogService.orderCancelledKey(order.id))!!
            assertEquals(ProcessPendingOrdersUseCase.RUN_END_REACHED, log.reasonCode)
        }
        assertEquals(orders.size, auditCount(TradeAuditEventType.ORDER_CANCELLED))
        assertEquals(RunStatus.COMPLETED, database.strategyRunDao().findById(runId)!!.status)
    }

    private fun reconcileTerminalOrders() =
        BJStockMigrations.reconcileLegacyTerminalOrderAudits(database.openHelper.writableDatabase)

    private fun emptyDecisionSourceCount(): Long =
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM trade_audit_logs WHERE decision_source = ''")
            .use { it.moveToFirst(); it.getLong(0) }

    private fun assertLedgerGroup(
        ledger: List<CashLedgerEntity>,
        execution: ExecutionEntity,
        expected: Map<String, Long>,
    ) {
        val group = ledger.filter {
            it.referenceType == CashLedgerReferenceTypes.EXECUTION && it.referenceId == execution.id
        }
        assertEquals(expected, group.associate { it.eventKey to it.amount })
    }

    private inner class Harness(
        auditDao: TradeAuditLogDao = database.tradeAuditLogDao(),
        ledgerDao: CashLedgerDao = database.cashLedgerDao(),
        evaluationDao: StockEvaluationDao = database.stockEvaluationDao(),
        runDao: StrategyRunDao = database.strategyRunDao(),
    ) {
        val audit = TradeAuditLogService(auditDao) { Instant.EPOCH }
        val cash = CashLedgerService(ledgerDao) { Instant.EPOCH }
        val fills = VirtualFillService(
            database = database,
            orderDao = database.orderDao(),
            executionDao = database.executionDao(),
            positionDao = database.positionDao(),
            cashLedger = cash,
            audit = audit,
            now = { Instant.EPOCH },
        )
        val processEvaluation = ProcessEvaluationUseCase(
            database = database,
            evaluationDao = evaluationDao,
            strategyRunDao = database.strategyRunDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
            audit = audit,
            now = { Instant.EPOCH },
        )
        val processPending = ProcessPendingOrdersUseCase(
            strategyRunDao = runDao,
            orderDao = database.orderDao(),
            evaluationDao = evaluationDao,
            marketDailyBarDao = database.marketDailyBarDao(),
            positionDao = database.positionDao(),
            cashLedger = cash,
            fills = fills,
            policyService = policyService,
        )
        val evaluateRun = EvaluateStrategyRunUseCase(
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            evaluations = StrategyEvaluationRepository(database, evaluationDao) { Instant.EPOCH },
            loader = StrategyEvaluationLoader(
                strategyService = strategyService,
                factorDao = database.factorDao(),
                factorValues = factorValues,
                signalRuleDao = database.strategySignalRuleDao(),
                signalRuleEngine = SignalRuleEngine(database.marketDailyBarDao()),
            ),
            audit = audit,
        )
    }

    private class FailingAuditDao(
        private val delegate: TradeAuditLogDao,
        private val failOn: TradeAuditEventType,
    ) : TradeAuditLogDao by delegate {
        override suspend fun insert(entity: TradeAuditLogEntity): Long {
            check(entity.eventType != failOn) { "injected audit failure" }
            return delegate.insert(entity)
        }
    }

    /** Fails the [failAt]-th insert of [failOn] (1-based) within this instance. */
    private class NthAuditFailureDao(
        private val delegate: TradeAuditLogDao,
        private val failOn: TradeAuditEventType,
        private val failAt: Int,
    ) : TradeAuditLogDao by delegate {
        private var seen = 0

        override suspend fun insert(entity: TradeAuditLogEntity): Long {
            if (entity.eventType == failOn) {
                seen += 1
                check(seen != failAt) { "injected audit failure" }
            }
            return delegate.insert(entity)
        }
    }

    private class FailingCompletionRunDao(private val delegate: StrategyRunDao) : StrategyRunDao by delegate {
        override suspend fun updateStatus(id: Long, status: RunStatus, updatedAt: Instant) {
            check(status != RunStatus.COMPLETED) { "injected run completion failure" }
            delegate.updateStatus(id, status, updatedAt)
        }
    }

    private class FailingLedgerDao(
        private val delegate: CashLedgerDao,
        private val failOn: CashLedgerEventType,
    ) : CashLedgerDao by delegate {
        override suspend fun insert(entity: CashLedgerEntity): Long {
            check(entity.eventType != failOn) { "injected ledger failure" }
            return delegate.insert(entity)
        }
    }

    private class FailingDetailDao(private val delegate: StockEvaluationDao) : StockEvaluationDao by delegate {
        override suspend fun insertDetail(entity: StockEvaluationDetailEntity): Long =
            error("injected evaluation detail failure")
    }

    private suspend fun createPendingBuy(withBar: Boolean = true): com.mirunubi.bjstock.core.database.entity.OrderEntity {
        val evaluationId = insertEvaluation(friday, TradeDecision.BUY)
        assertEquals(PaperTradeAction.ORDER_CREATED, Harness().processEvaluation(evaluationId).action)
        if (withBar) insertBar(monday, open = 50_000, close = 50_000)
        return database.orderDao().findByRun(runId).single()
    }

    private suspend fun assertUntouchedPendingBuy() {
        val order = database.orderDao().findByRun(runId).single()
        assertEquals(OrderStatus.PENDING_EXECUTION, order.status)
        assertEquals(0L, order.quantity)
        assertNull(order.executedAt)
        assertEquals(0, database.executionDao().countByRun(runId))
        assertEquals(1, database.cashLedgerDao().countByRun(runId))
        assertEquals(100_000_000L, CashLedgerService(database.cashLedgerDao()).reconstructCash(runId))
        assertNull(database.positionDao().find(runId, instrumentId))
        assertEquals(0, auditCount(TradeAuditEventType.EXECUTION_FILLED))
    }

    private suspend fun seedOpenPosition(quantity: Long): com.mirunubi.bjstock.core.database.entity.PositionEntity {
        val order = createPendingBuy()
        Harness().fills.executeBuy(order, monday, 50_000, quantity, 0L)
        return database.positionDao().find(runId, instrumentId)!!
    }

    private suspend fun newRun(versionId: Long, initialCash: Long): Long = runService.createReadyRun(
        strategyVersionId = versionId,
        runName = "Atomic ${System.nanoTime()}",
        startDate = friday,
        initialCashWon = initialCash,
        instrumentIds = listOf(instrumentId),
    )

    private suspend fun auditByKey(key: String) = database.tradeAuditLogDao().findByEventKey(key)

    private suspend fun auditCount(type: TradeAuditEventType) =
        database.tradeAuditLogDao().findByRun(runId).count { it.eventType == type }

    private fun deleteAudit(key: String) {
        database.openHelper.writableDatabase.execSQL("DELETE FROM trade_audit_logs WHERE event_key = ?", arrayOf(key))
    }

    private suspend fun insertEvaluation(
        date: LocalDate,
        decision: TradeDecision,
        run: Long = runId,
    ): Long = database.stockEvaluationDao().insertEvaluation(
        StockEvaluationEntity(
            strategyRunId = run,
            instrumentId = instrumentId,
            evaluationDate = date,
            quantScore = 800_000L,
            finalScore = 800_000L,
            quantDecision = decision,
            finalDecision = decision,
            createdAt = Instant.EPOCH,
        ),
    )

    private suspend fun insertFactorValue(date: LocalDate) {
        database.factorDao().insertValue(
            FactorValueEntity(
                instrumentId = instrumentId,
                factorId = factorId,
                evaluationDate = date,
                rawValue = "1",
                normalizedScore = StrategyScoreMath.scoreToStored(BigDecimal("80")),
                source = "TEST",
                calculationVersion = FactorCalculationVersions.V1,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun insertBar(date: LocalDate, open: Long, close: Long) {
        database.marketDailyBarDao().insert(
            MarketDailyBarEntity(
                instrumentId = instrumentId,
                tradeDate = date,
                openPrice = open,
                highPrice = maxOf(open, close),
                lowPrice = minOf(open, close),
                closePrice = close,
                volume = 1,
                tradingValue = close,
                source = "TEST",
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun createActiveFactorOnly(): Long {
        val strategyId = strategyService.createStrategy("FAC_${System.nanoTime()}", "Factor")
        val draft = strategyService.createDraftVersion(strategyId)
        strategyService.upsertDraftWeight(
            strategyVersionId = draft,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        assertTrue(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
        return draft
    }

    private suspend fun createActiveWithSellRule(): Long {
        val strategyId = strategyService.createStrategy("RULE_${System.nanoTime()}", "Rule")
        val draft = strategyService.createDraftVersion(strategyId)
        strategyService.upsertDraftWeight(
            strategyVersionId = draft,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        strategyService.upsertDraftSignalRule(
            strategyVersionId = draft,
            ruleCode = "SELL_3",
            operator = SignalOperator.GTE,
            thresholdValue = "3.0",
            action = SignalAction.SELL,
            priority = 10,
        )
        assertTrue(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
        return draft
    }
}
