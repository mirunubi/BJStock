package com.mirunubi.bjstock.core.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradeAction
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.paper.ProcessEvaluationUseCase
import com.mirunubi.bjstock.core.paper.ProcessPendingOrdersUseCase
import com.mirunubi.bjstock.core.paper.VirtualFillService
import com.mirunubi.bjstock.core.strategy.EvaluateStrategyRunUseCase
import com.mirunubi.bjstock.core.strategy.SignalRuleEngine
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationLoader
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationRepository
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TradeAuditLogTest {
    private lateinit var database: BJStockDatabase
    private lateinit var audit: TradeAuditLogService
    private lateinit var strategyService: StrategyVersionService
    private lateinit var runService: StrategyRunService
    private lateinit var evaluateRun: EvaluateStrategyRunUseCase
    private lateinit var processEvaluation: ProcessEvaluationUseCase
    private lateinit var processPending: ProcessPendingOrdersUseCase
    private lateinit var factorValues: FactorValueRepository
    private var instrumentId = 0L
    private var factorId = 0L
    private val signalDay = LocalDate.of(2026, 9, 18)
    private val prevDay = LocalDate.of(2026, 9, 17)
    private val fillDay = LocalDate.of(2026, 9, 21)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        audit = TradeAuditLogService(database.tradeAuditLogDao()) { Instant.EPOCH }
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
        val cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        val policyService = PaperTradingPolicyService(
            policyDao = database.paperTradingPolicyDao(),
            now = { Instant.EPOCH },
        )
        runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = cashLedger,
            policyService = policyService,
            factorRegistry = SystemFactorRegistryFactory.create(),
            defaultPolicyTemplate = { PaperTradingPolicy.ZERO_COST },
            now = { Instant.EPOCH },
        )
        val loader = StrategyEvaluationLoader(
            strategyService = strategyService,
            factorDao = database.factorDao(),
            factorValues = factorValues,
            signalRuleDao = database.strategySignalRuleDao(),
            signalRuleEngine = SignalRuleEngine(database.marketDailyBarDao()),
        )
        evaluateRun = EvaluateStrategyRunUseCase(
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            evaluations = StrategyEvaluationRepository(database, database.stockEvaluationDao()),
            loader = loader,
            audit = audit,
        )
        processEvaluation = ProcessEvaluationUseCase(
            evaluationDao = database.stockEvaluationDao(),
            strategyRunDao = database.strategyRunDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
            audit = audit,
            now = { Instant.EPOCH },
        )
        val fills = VirtualFillService(
            database = database,
            orderDao = database.orderDao(),
            executionDao = database.executionDao(),
            positionDao = database.positionDao(),
            cashLedger = cashLedger,
            audit = audit,
            now = { Instant.EPOCH },
        )
        processPending = ProcessPendingOrdersUseCase(
            strategyRunDao = database.strategyRunDao(),
            orderDao = database.orderDao(),
            evaluationDao = database.stockEvaluationDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            positionDao = database.positionDao(),
            cashLedger = cashLedger,
            fills = fills,
            policyService = policyService,
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "000660", name = "SK Hynix"),
        )
        insertBar(prevDay, 100)
        insertBar(signalDay, 103)
        insertBar(fillDay, open = 73500, close = 73500)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ruleReasonFlow_writesTriggeredOrderAndFill() = runBlocking {
        val versionId = createActiveWithSellRule()
        val runId = runService.createReadyRun(
            strategyVersionId = versionId,
            runName = "Rule Audit",
            startDate = signalDay,
            initialCashWon = 100_000_000L,
            instrumentIds = listOf(instrumentId),
        )
        database.positionDao().insert(
            com.mirunubi.bjstock.core.database.entity.PositionEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                quantity = 100L,
                averagePrice = 70_000L,
                realizedProfit = 0L,
                updatedAt = Instant.EPOCH,
            ),
        )

        val result = evaluateRun(runId, instrumentId, signalDay)
        assertEquals(DecisionSource.SIGNAL_RULE, result.decisionSource)
        val sell = processEvaluation(result.persistedEvaluationId!!)
        assertEquals(PaperTradeAction.ORDER_CREATED, sell.action)
        processPending(runId, asOfMarketDate = fillDay)

        val events = audit.findByRun(runId).map { it.eventType }.toSet()
        assertTrue(events.contains(TradeAuditEventType.RULE_TRIGGERED))
        assertTrue(events.contains(TradeAuditEventType.EVALUATION_DECIDED))
        assertTrue(events.contains(TradeAuditEventType.ORDER_CREATED))
        assertTrue(events.contains(TradeAuditEventType.EXECUTION_FILLED))
        val triggered = audit.findByRun(runId).first { it.eventType == TradeAuditEventType.RULE_TRIGGERED }
        assertEquals("DAILY_CHANGE_PCT", triggered.metricCode)
        assertEquals("3.00", triggered.thresholdValue)
        assertTrue(triggered.observedValue!!.startsWith("3."))
    }

    @Test
    fun factorReason_whenNoRuleTriggers() = runBlocking {
        database.marketDailyBarDao().update(
            database.marketDailyBarDao().findByInstrumentAndDate(instrumentId, signalDay)!!.copy(closePrice = 101),
        )
        val versionId = createActiveFactorOnly()
        val runId = runService.createReadyRun(
            strategyVersionId = versionId,
            runName = "Factor Audit",
            startDate = signalDay,
            initialCashWon = 100_000_000L,
            instrumentIds = listOf(instrumentId),
        )
        database.factorDao().insertValue(
            FactorValueEntity(
                instrumentId = instrumentId,
                factorId = factorId,
                evaluationDate = signalDay,
                rawValue = "1",
                normalizedScore = StrategyScoreMath.scoreToStored(BigDecimal("80")),
                source = "TEST",
                calculationVersion = FactorCalculationVersions.V1,
                createdAt = Instant.EPOCH,
            ),
        )
        val result = evaluateRun(runId, instrumentId, signalDay)
        assertEquals(DecisionSource.FACTOR_STRATEGY, result.decisionSource)
        val decided = audit.findByRun(runId).first { it.eventType == TradeAuditEventType.EVALUATION_DECIDED }
        assertEquals(DecisionSource.FACTOR_STRATEGY, decided.decisionSource)
        assertTrue(decided.reasonText!!.contains("Quant score"))
    }

    @Test
    fun orderSkip_logsPositionAlreadyOpen() = runBlocking {
        val versionId = createActiveFactorOnly()
        val runId = runService.createReadyRun(
            strategyVersionId = versionId,
            runName = "Skip Audit",
            startDate = signalDay,
            initialCashWon = 100_000_000L,
            instrumentIds = listOf(instrumentId),
        )
        database.positionDao().insert(
            com.mirunubi.bjstock.core.database.entity.PositionEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                quantity = 10L,
                averagePrice = 50_000L,
                realizedProfit = 0L,
                updatedAt = Instant.EPOCH,
            ),
        )

        val secondBuy = database.stockEvaluationDao().insertEvaluation(
            com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationDate = signalDay,
                quantScore = 800_000L,
                finalScore = 800_000L,
                quantDecision = TradeDecision.BUY,
                finalDecision = TradeDecision.BUY,
                createdAt = Instant.EPOCH,
            ),
        )
        val skipped = processEvaluation(secondBuy)
        assertEquals(PaperTradeAction.ORDER_SKIPPED, skipped.action)
        val log = audit.findByRun(runId).first { it.eventType == TradeAuditEventType.ORDER_SKIPPED }
        assertEquals("POSITION_ALREADY_OPEN", log.reasonCode)
    }

    @Test
    fun auditIdempotency_duplicateKeysDoNotDuplicateRows() = runBlocking {
        val versionId = createActiveFactorOnly()
        val actualRunId = runService.createReadyRun(
            strategyVersionId = versionId,
            runName = "idem",
            startDate = signalDay,
            initialCashWon = 1_000_000L,
            instrumentIds = listOf(instrumentId),
        )
        audit.append(
            strategyRunId = actualRunId,
            eventType = TradeAuditEventType.EVALUATION_DECIDED,
            eventKey = "evaluation:99:decision",
            reasonText = "first",
        )
        audit.append(
            strategyRunId = actualRunId,
            eventType = TradeAuditEventType.EVALUATION_DECIDED,
            eventKey = "evaluation:99:decision",
            reasonText = "second",
        )
        assertEquals(1, database.tradeAuditLogDao().countByRun(actualRunId))
    }

    private suspend fun createActiveWithSellRule(): Long {
        val strategyId = strategyService.createStrategy("RULE", "Rule")
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

    private suspend fun insertBar(date: LocalDate, close: Long, open: Long = close) {
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
                source = "KIS",
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }
}
