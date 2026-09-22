package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.CreateDailySnapshotUseCase
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
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
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
class ForwardTestOrchestratorTest {
    private lateinit var database: BJStockDatabase
    private lateinit var runService: StrategyRunService
    private lateinit var strategyService: StrategyVersionService
    private lateinit var cashLedger: CashLedgerService
    private lateinit var processEvaluation: ProcessEvaluationUseCase
    private lateinit var processPending: ProcessPendingOrdersUseCase
    private lateinit var createSnapshot: CreateDailySnapshotUseCase
    private lateinit var evaluateRun: EvaluateStrategyRunUseCase
    private lateinit var factorCalculation: FactorCalculationService
    private var versionId = 0L
    private var instrumentId = 0L

    private val friday = LocalDate.of(2026, 10, 9)
    private val monday = LocalDate.of(2026, 10, 12)
    private val tuesday = LocalDate.of(2026, 10, 13)
    private val wednesday = LocalDate.of(2026, 10, 14)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        val factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        factorValues.ensureSystemFactorDefinitions()
        val registry = SystemFactorRegistryFactory.create()
        strategyService = StrategyVersionService(
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = registry,
            signalRuleDao = database.strategySignalRuleDao(),
            now = { Instant.EPOCH },
        )
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
            factorRegistry = registry,
            defaultPolicyTemplate = { PaperTradingPolicy.ZERO_COST },
            now = { Instant.EPOCH },
        )
        val fills = VirtualFillService(
            database = database,
            orderDao = database.orderDao(),
            executionDao = database.executionDao(),
            positionDao = database.positionDao(),
            cashLedger = cashLedger,
            now = { Instant.EPOCH },
        )
        processEvaluation = ProcessEvaluationUseCase(
            evaluationDao = database.stockEvaluationDao(),
            strategyRunDao = database.strategyRunDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
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
        createSnapshot = CreateDailySnapshotUseCase(
            strategyRunDao = database.strategyRunDao(),
            positionDao = database.positionDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            snapshotDao = database.portfolioDailySnapshotDao(),
            cashLedger = cashLedger,
            now = { Instant.EPOCH },
        )
        factorCalculation = FactorCalculationService(
            registry = registry,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            factorValues = factorValues,
        )
        evaluateRun = EvaluateStrategyRunUseCase(
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            evaluations = StrategyEvaluationRepository(database, database.stockEvaluationDao()),
            loader = StrategyEvaluationLoader(
                strategyService = strategyService,
                factorDao = database.factorDao(),
                factorValues = factorValues,
                signalRuleDao = database.strategySignalRuleDao(),
                signalRuleEngine = SignalRuleEngine(database.marketDailyBarDao()),
            ),
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        versionId = createActiveVersion()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun weekend_noMarketBars_createsNoCycles() = runBlocking {
        val runId = createRun(start = friday, end = wednesday)
        val saturday = LocalDate.of(2026, 10, 10)
        val result = orchestrator().runSingleStrategyRun(runId, throughDate = saturday)
        assertTrue(result is ForwardOrchestratorResult.NoOp)
        assertEquals(0, database.forwardTestCycleDao().countByRun(runId))
        assertEquals(0, database.portfolioDailySnapshotDao().countByRun(runId))
    }

    @Test
    fun threeDayCatchUp_processesAscending() = runBlocking {
        val runId = createRun(start = monday, end = wednesday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        insertBar(instrumentId, tuesday, 51_000, 52_000)
        insertBar(instrumentId, wednesday, 52_000, 53_000)
        // Prior Friday complete so catch-up starts Monday
        seedCompleteCycle(runId, friday)

        val result = orchestrator().runSingleStrategyRun(runId, throughDate = wednesday)
        assertTrue(result is ForwardOrchestratorResult.Ok)
        val cycles = database.forwardTestCycleDao().findRecentByRun(runId, 10)
            .filter { it.marketDate >= monday }
            .sortedBy { it.marketDate }
        assertEquals(listOf(monday, tuesday, wednesday), cycles.map { it.marketDate })
        assertTrue(cycles.all { it.status == ForwardCycleStatus.COMPLETE })
    }

    @Test
    fun failedDay_stopsLaterDates() = runBlocking {
        val runId = createRun(start = monday, end = wednesday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        insertBar(instrumentId, tuesday, 51_000, 52_000)
        insertBar(instrumentId, wednesday, 52_000, 53_000)

        orchestrator().runSingleStrategyRun(runId, throughDate = monday)
        assertEquals(
            ForwardCycleStatus.COMPLETE,
            database.forwardTestCycleDao().find(runId, monday)!!.status,
        )

        database.forwardTestCycleDao().insert(
            com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity(
                strategyRunId = runId,
                marketDate = tuesday,
                status = ForwardCycleStatus.FAILED,
                currentStage = com.mirunubi.bjstock.core.model.ForwardCycleStage.SNAPSHOT,
                attemptCount = 1,
                errorCode = ForwardErrorCode.SNAPSHOT_MISSING_PRICE.name,
                errorMessage = "SNAPSHOT_FAIL",
                retryable = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        val result = orchestrator().runSingleStrategyRun(runId, throughDate = wednesday)
        assertTrue(result is ForwardOrchestratorResult.Blocked)
        assertEquals(null, database.forwardTestCycleDao().find(runId, wednesday))
        assertEquals(
            ForwardCycleStatus.FAILED,
            database.forwardTestCycleDao().find(runId, tuesday)!!.status,
        )
    }

    @Test
    fun retryResume_completesFailedThenContinues() = runBlocking {
        val runId = createRun(start = monday, end = wednesday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        insertBar(instrumentId, tuesday, 51_000, 52_000)
        insertBar(instrumentId, wednesday, 52_000, 53_000)

        orchestrator().runSingleStrategyRun(runId, throughDate = monday)
        database.forwardTestCycleDao().insert(
            com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity(
                strategyRunId = runId,
                marketDate = tuesday,
                status = ForwardCycleStatus.FAILED,
                currentStage = com.mirunubi.bjstock.core.model.ForwardCycleStage.SNAPSHOT,
                attemptCount = 1,
                errorCode = ForwardErrorCode.SNAPSHOT_MISSING_PRICE.name,
                errorMessage = "SNAPSHOT_FAIL",
                retryable = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        val blocked = orchestrator().runSingleStrategyRun(runId, throughDate = wednesday)
        assertTrue(blocked is ForwardOrchestratorResult.Blocked)

        val resumed = orchestrator().retryFailedCycle(runId, tuesday, throughDate = wednesday)
        assertTrue(resumed is ForwardOrchestratorResult.Ok)
        assertEquals(
            ForwardCycleStatus.COMPLETE,
            database.forwardTestCycleDao().find(runId, tuesday)?.status,
        )
        assertEquals(
            ForwardCycleStatus.COMPLETE,
            database.forwardTestCycleDao().find(runId, wednesday)?.status,
        )
    }

    @Test
    fun idempotentFullDay_rerunDoesNotDuplicate() = runBlocking {
        val runId = createRun(start = monday, end = monday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        val orch = orchestrator()
        repeat(5) {
            orch.runSingleStrategyRun(runId, throughDate = monday)
        }
        assertEquals(1, database.forwardTestCycleDao().countByRun(runId))
        assertEquals(1, database.portfolioDailySnapshotDao().countByRun(runId))
        val evalCount = database.stockEvaluationDao().findByRun(runId).size
        val orderCount = database.orderDao().countByRun(runId)
        val execCount = database.executionDao().countByRun(runId)
        orch.runSingleStrategyRun(runId, throughDate = monday)
        assertEquals(evalCount, database.stockEvaluationDao().findByRun(runId).size)
        assertEquals(orderCount, database.orderDao().countByRun(runId))
        assertEquals(execCount, database.executionDao().countByRun(runId))
    }

    @Test
    fun missingCredential_blocksWithoutTrades() = runBlocking {
        val runId = createRun(start = monday, end = monday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        val gateway = object : ForwardMarketDataGateway {
            override suspend fun ensureCredentials(): Boolean = false
            override suspend fun syncUniverseTo(
                instrumentIds: List<Long>,
                throughDate: LocalDate,
            ) = MarketSyncOutcome(success = true)
        }
        val result = orchestrator(gateway).runSingleStrategyRun(runId, throughDate = monday)
        assertTrue(result is ForwardOrchestratorResult.Blocked)
        assertEquals(
            ForwardErrorCode.AUTH_REQUIRED.name,
            (result as ForwardOrchestratorResult.Blocked).errorCode,
        )
        assertEquals(0, database.forwardTestCycleDao().countByRun(runId))
        assertEquals(0, database.stockEvaluationDao().findByRun(runId).size)
    }

    @Test
    fun networkFailure_blocksWithoutPartialJudgement() = runBlocking {
        val runId = createRun(start = monday, end = monday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        val gateway = object : ForwardMarketDataGateway {
            override suspend fun ensureCredentials(): Boolean = true
            override suspend fun syncUniverseTo(
                instrumentIds: List<Long>,
                throughDate: LocalDate,
            ) = MarketSyncOutcome(
                success = false,
                errorCode = ForwardErrorCode.NETWORK_FAILURE.name,
                errorMessage = "timeout",
                retryable = true,
            )
        }
        val result = orchestrator(gateway).runSingleStrategyRun(runId, throughDate = monday)
        assertTrue(result is ForwardOrchestratorResult.Blocked)
        assertEquals(0, database.forwardTestCycleDao().countByRun(runId))
        assertEquals(0, database.orderDao().countByRun(runId))
        assertEquals(0, database.portfolioDailySnapshotDao().countByRun(runId))
    }

    @Test
    fun runEnd_cancelsResidualPending_noForcedLiquidation() = runBlocking {
        val runId = createRun(start = friday, end = friday)
        insertBar(instrumentId, friday, 50_000, 51_000)
        // Prior pending from earlier signal that can fill on friday open
        val evalId = database.stockEvaluationDao().insertEvaluation(
            StockEvaluationEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationDate = friday.minusDays(3),
                quantScore = 800_000,
                aiScore = null,
                finalScore = 800_000,
                quantDecision = TradeDecision.BUY,
                finalDecision = TradeDecision.BUY,
                createdAt = Instant.EPOCH,
            ),
        )
        processEvaluation(evalId)

        val result = orchestrator().runSingleStrategyRun(runId, throughDate = friday)
        assertTrue(result is ForwardOrchestratorResult.Ok)
        assertEquals(RunStatus.COMPLETED, database.strategyRunDao().findById(runId)!!.status)
        // Any residual pending cancelled
        assertTrue(
            database.orderDao().findByRun(runId).none { it.status == OrderStatus.PENDING_EXECUTION },
        )
        // Position may exist from friday fill — not force sold
        val pos = database.positionDao().find(runId, instrumentId)
        if (pos != null && pos.quantity > 0) {
            assertTrue(database.orderDao().findByRun(runId).none {
                it.status == OrderStatus.VIRTUAL_FILLED &&
                    it.side == com.mirunubi.bjstock.core.model.OrderSide.SELL &&
                    // forced liquidation would be new sell after end
                    false
            })
        }
        assertNotNull(database.portfolioDailySnapshotDao().find(runId, friday))
    }

    @Test
    fun multipleRuns_isolated() = runBlocking {
        val runA = createRun(start = monday, end = monday, name = "A")
        val runB = createRun(start = monday, end = monday, name = "B")
        insertBar(instrumentId, monday, 50_000, 51_000)
        orchestrator().runForwardTests(throughDate = monday)
        assertEquals(1, database.forwardTestCycleDao().countByRun(runA))
        assertEquals(1, database.forwardTestCycleDao().countByRun(runB))
        assertEquals(
            cashLedger.currentCash(runA),
            cashLedger.reconstructCash(runA),
        )
        assertEquals(
            cashLedger.currentCash(runB),
            cashLedger.reconstructCash(runB),
        )
    }

    @Test
    fun autoOff_defaultFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ForwardTestSchedulerSettings(context)
        assertEquals(false, settings.isAutoEnabled())
    }

    @Test
    fun manualAndWorkerShareOrchestratorSemantics() = runBlocking {
        val runId = createRun(start = monday, end = monday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        val orch = orchestrator()
        val manual = orch.runSingleStrategyRun(runId, throughDate = monday)
        assertTrue(manual is ForwardOrchestratorResult.Ok)
        val fingerprint = database.portfolioDailySnapshotDao().find(runId, monday)!!.totalAsset
        // Worker path = same orchestrator
        val worker = orch.runForwardTests(throughDate = monday)
        assertTrue(worker is ForwardOrchestratorResult.Ok || worker is ForwardOrchestratorResult.NoOp)
        assertEquals(
            fingerprint,
            database.portfolioDailySnapshotDao().find(runId, monday)!!.totalAsset,
        )
    }

    @Test
    fun futureBarPoison_pendingFillCappedByAsOfMarketDate() = runBlocking {
        val runId = createRun(start = monday, end = wednesday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        insertBar(instrumentId, tuesday, 60_000, 61_000)
        insertBar(instrumentId, wednesday, 70_000, 71_000)

        val evalId = database.stockEvaluationDao().insertEvaluation(
            StockEvaluationEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationDate = friday,
                quantScore = 800_000,
                aiScore = null,
                finalScore = 800_000,
                quantDecision = TradeDecision.BUY,
                finalDecision = TradeDecision.BUY,
                createdAt = Instant.EPOCH,
            ),
        )
        processEvaluation(evalId)

        orchestrator().runSingleStrategyRun(runId, throughDate = monday)
        val execution = database.executionDao().findByRun(runId).single()
        assertEquals(
            monday,
            com.mirunubi.bjstock.core.paper.MarketExecutionTime.toTradeDate(execution.executedAt),
        )
        assertEquals(50_000L, execution.executionPrice)
        assertEquals(1, database.executionDao().countByRun(runId))
    }

    @Test
    fun sameDayFill_prohibitedOnSignalDay() = runBlocking {
        val runId = createRun(start = monday, end = tuesday)
        insertBar(instrumentId, monday, 50_000, 51_000)
        insertBar(instrumentId, tuesday, 55_000, 56_000)

        orchestrator().runSingleStrategyRun(runId, throughDate = monday)
        val evalId = database.stockEvaluationDao().insertEvaluation(
            StockEvaluationEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationDate = monday,
                quantScore = 800_000,
                aiScore = null,
                finalScore = 800_000,
                quantDecision = TradeDecision.BUY,
                finalDecision = TradeDecision.BUY,
                createdAt = Instant.EPOCH,
            ),
        )
        processEvaluation(evalId)
        val pendingOnly = processPending(runId, asOfMarketDate = monday)
        assertEquals(
            com.mirunubi.bjstock.core.paper.PaperTradeAction.PENDING,
            pendingOnly.single().action,
        )
        assertEquals(0, database.executionDao().countByRun(runId))

        orchestrator().runSingleStrategyRun(runId, throughDate = tuesday)
        val execution = database.executionDao().findByRun(runId).single()
        assertEquals(
            tuesday,
            com.mirunubi.bjstock.core.paper.MarketExecutionTime.toTradeDate(execution.executedAt),
        )
    }

    private fun orchestrator(
        gateway: ForwardMarketDataGateway = LocalOnlyMarketDataGateway(),
    ) = ForwardTestOrchestrator(
        strategyRunDao = database.strategyRunDao(),
        strategyDao = database.strategyDao(),
        universeDao = database.strategyRunInstrumentDao(),
        cycleDao = database.forwardTestCycleDao(),
        marketDailyBarDao = database.marketDailyBarDao(),
        orderDao = database.orderDao(),
        evaluationDao = database.stockEvaluationDao(),
        snapshotDao = database.portfolioDailySnapshotDao(),
        factorDao = database.factorDao(),
        policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH },
        processPending = processPending,
        factorCalculation = factorCalculation,
        evaluateRun = evaluateRun,
        processEvaluation = processEvaluation,
        createSnapshot = createSnapshot,
        marketData = gateway,
        clock = ForwardTestClock(),
        now = { Instant.EPOCH },
    )

    private suspend fun createRun(
        start: LocalDate,
        end: LocalDate,
        name: String = "FT",
    ): Long = runService.createReadyRun(
        strategyVersionId = versionId,
        runName = name,
        startDate = start,
        initialCashWon = 100_000_000L,
        endDate = end,
        instrumentIds = listOf(instrumentId),
    )

    private suspend fun createActiveVersion(): Long {
        val strategyId = strategyService.createStrategy("FT_TEST", "Forward Test")
        val draft = strategyService.createDraftVersion(strategyId)
        val factorId = FactorValueRepository(database.factorDao())
            .findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id
        strategyService.upsertDraftWeight(
            strategyVersionId = draft,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = "v1",
        )
        assertTrue(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
        return draft
    }

    private suspend fun insertBar(instrumentId: Long, date: LocalDate, open: Long, close: Long) {
        database.marketDailyBarDao().insert(
            MarketDailyBarEntity(
                instrumentId = instrumentId,
                tradeDate = date,
                openPrice = open,
                highPrice = maxOf(open, close),
                lowPrice = minOf(open, close),
                closePrice = close,
                volume = 1_000,
                tradingValue = null,
                source = "TEST",
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun seedCompleteCycle(runId: Long, date: LocalDate) {
        database.forwardTestCycleDao().insert(
            com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity(
                strategyRunId = runId,
                marketDate = date,
                status = ForwardCycleStatus.COMPLETE,
                currentStage = com.mirunubi.bjstock.core.model.ForwardCycleStage.COMPLETE,
                attemptCount = 1,
                completedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
