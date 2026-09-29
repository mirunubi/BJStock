package com.mirunubi.bjstock.feature.performance

import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.forward.ForwardMarketDataGateway
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestConfig
import com.mirunubi.bjstock.core.forward.ForwardTestExecutionCoordinator
import com.mirunubi.bjstock.core.forward.ForwardTestOrchestrator
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.ForwardTestSchedulerSettings
import com.mirunubi.bjstock.core.forward.MarketSyncOutcome
import com.mirunubi.bjstock.core.model.RunStatus
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
import com.mirunubi.bjstock.core.theme.ThemeService
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ForwardTestViewModelMarkReadyTest {
    private lateinit var context: Context
    private lateinit var database: BJStockDatabase
    private lateinit var runService: StrategyRunService
    private lateinit var strategyService: StrategyVersionService
    private lateinit var factorValues: FactorValueRepository
    private lateinit var gateway: CountingGateway
    private var viewModel: ForwardTestViewModel? = null
    private var instrumentId = 0L
    private var activeVersionId = 0L
    private val startDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        factorValues.ensureSystemFactorDefinitions()
        strategyService = StrategyVersionService(
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = SystemFactorRegistryFactory.create(),
            signalRuleDao = database.strategySignalRuleDao(),
            now = { Instant.EPOCH },
        )
        runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH },
            policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH },
            factorRegistry = SystemFactorRegistryFactory.create(),
            defaultPolicyTemplate = { PaperTradingPolicy.ZERO_COST },
            now = { Instant.EPOCH },
        )
        gateway = CountingGateway()
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        activeVersionId = createActiveVersion()
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun canMarkReady_onlyForDraft() {
        RunStatus.entries.forEach { status ->
            assertEquals(
                status.name,
                status == RunStatus.DRAFT,
                ForwardTestUiState(selectedRunStatus = status).canMarkReady,
            )
        }
        assertFalse(ForwardTestUiState(selectedRunStatus = null).canMarkReady)
    }

    @Test
    fun draftSelected_exposesMarkReady() {
        val runId = createDraft()
        createViewModel()

        val state = awaitState { it.selectedRunId == runId && it.selectedRunStatus != null }
        assertEquals(RunStatus.DRAFT, state.selectedRunStatus)
        assertTrue(state.canMarkReady)
        assertNull(state.readyConfirmation)
    }

    @Test
    fun readySelected_hidesMarkReady_andRequestOpensNoConfirmation() {
        val runId = createDraft(warmup = true)
        runBlocking { runService.markReady(runId) }
        val vm = createViewModel()

        val state = awaitState { it.selectedRunStatus != null }
        assertEquals(RunStatus.READY, state.selectedRunStatus)
        assertFalse(state.canMarkReady)

        vm.requestMarkReady()
        val after = awaitState { it.message != null }
        assertNull(after.readyConfirmation)
        assertEquals(1, count("paper_trading_policies"))
        assertEquals(1, count("cash_ledger"))
    }

    @Test
    fun cancel_doesNotCallMarkReady_andLeavesDbUnchanged() {
        val runId = createDraft(warmup = true)
        val vm = createViewModel()
        awaitState { it.canMarkReady }
        val before = snapshotCounts()

        vm.requestMarkReady()
        val confirmation = awaitState { it.readyConfirmation != null }.readyConfirmation!!
        assertEquals(runId, confirmation.runId)
        assertEquals("VM Run", confirmation.runName)
        assertTrue(confirmation.strategyLabel.endsWith("V1"))
        assertEquals(startDate, confirmation.startDate)
        assertEquals(100_000_000L, confirmation.initialCash)
        assertEquals(1, confirmation.universeCount)

        vm.cancelMarkReady()
        val state = vm.uiState.value
        assertNull(state.readyConfirmation)
        assertTrue(state.canMarkReady)
        assertEquals(RunStatus.DRAFT, runBlocking { runService.findById(runId) }!!.status)
        assertEquals(before, snapshotCounts())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun confirm_marksReadyOnce_refreshesUi_andDoesNotRunForwardTest() {
        val runId = createDraft(warmup = true)
        val vm = createViewModel()
        awaitState { it.canMarkReady }

        vm.requestMarkReady()
        awaitState { it.readyConfirmation != null }
        vm.confirmMarkReady()
        vm.confirmMarkReady()

        val state = awaitState { it.message == "Run $runId is READY" && !it.loading }
        assertEquals(RunStatus.READY, state.selectedRunStatus)
        assertFalse(state.canMarkReady)
        assertNull(state.readyConfirmation)
        assertEquals(RunStatus.READY, runBlocking { runService.findById(runId) }!!.status)
        assertEquals(1, count("paper_trading_policies"))
        assertEquals(1, count("cash_ledger"))
        NO_TRADING_SIDE_EFFECT_TABLES.forEach { table -> assertEquals(table, 0, count(table)) }
        assertEquals(0, gateway.calls)
    }

    @Test
    fun confirmFailure_surfacesError_andStaysDraftWithoutFunding() {
        val runId = createDraft(warmup = false)
        val vm = createViewModel()
        awaitState { it.canMarkReady }

        vm.requestMarkReady()
        awaitState { it.readyConfirmation != null }
        vm.confirmMarkReady()

        val state = awaitState { it.message?.startsWith("Mark Ready failed") == true && !it.loading }
        assertTrue(state.message!!.contains("INSUFFICIENT_WARMUP_DATA"))
        assertEquals(RunStatus.DRAFT, state.selectedRunStatus)
        assertTrue(state.canMarkReady)
        assertEquals(RunStatus.DRAFT, runBlocking { runService.findById(runId) }!!.status)
        assertEquals(0, count("paper_trading_policies"))
        assertEquals(0, count("cash_ledger"))
        assertEquals(0, gateway.calls)
    }

    private fun createDraft(warmup: Boolean = false): Long = runBlocking {
        val runId = runService.createDraftRun(activeVersionId, "VM Run", startDate, 100_000_000L)
        runService.addInstrument(runId, instrumentId)
        if (warmup) insertWarmupBars(count = 30)
        runId
    }

    private fun createViewModel(): ForwardTestViewModel {
        val cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        val policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH }
        val registry = SystemFactorRegistryFactory.create()
        val repository = PerformanceAnalyticsRepository(
            strategyRunDao = database.strategyRunDao(),
            strategyDao = database.strategyDao(),
            snapshotDao = database.portfolioDailySnapshotDao(),
            orderDao = database.orderDao(),
            executionDao = database.executionDao(),
            positionDao = database.positionDao(),
            cashLedgerDao = database.cashLedgerDao(),
            evaluationDao = database.stockEvaluationDao(),
            policyDao = database.paperTradingPolicyDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
        )
        val fills = VirtualFillService(
            database = database,
            orderDao = database.orderDao(),
            executionDao = database.executionDao(),
            positionDao = database.positionDao(),
            cashLedger = cashLedger,
            now = { Instant.EPOCH },
        )
        val clock = ForwardTestClock(
            Clock.fixed(
                ZonedDateTime.of(2026, 9, 29, 20, 0, 0, 0, ForwardTestConfig.MARKET_ZONE).toInstant(),
                ForwardTestConfig.MARKET_ZONE,
            ),
        )
        val orchestrator = ForwardTestOrchestrator(
            strategyRunDao = database.strategyRunDao(),
            strategyDao = database.strategyDao(),
            universeDao = database.strategyRunInstrumentDao(),
            cycleDao = database.forwardTestCycleDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            orderDao = database.orderDao(),
            evaluationDao = database.stockEvaluationDao(),
            snapshotDao = database.portfolioDailySnapshotDao(),
            factorDao = database.factorDao(),
            policyService = policyService,
            processPending = ProcessPendingOrdersUseCase(
                strategyRunDao = database.strategyRunDao(),
                orderDao = database.orderDao(),
                evaluationDao = database.stockEvaluationDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                positionDao = database.positionDao(),
                cashLedger = cashLedger,
                fills = fills,
                policyService = policyService,
            ),
            factorCalculation = FactorCalculationService(
                registry = registry,
                instrumentDao = database.instrumentDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                factorValues = factorValues,
            ),
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
            ),
            processEvaluation = ProcessEvaluationUseCase(
                evaluationDao = database.stockEvaluationDao(),
                strategyRunDao = database.strategyRunDao(),
                orderDao = database.orderDao(),
                positionDao = database.positionDao(),
                now = { Instant.EPOCH },
            ),
            createSnapshot = CreateDailySnapshotUseCase(
                strategyRunDao = database.strategyRunDao(),
                positionDao = database.positionDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                snapshotDao = database.portfolioDailySnapshotDao(),
                cashLedger = cashLedger,
                now = { Instant.EPOCH },
            ),
            marketData = gateway,
            clock = clock,
            now = { Instant.EPOCH },
        )
        return ForwardTestViewModel(
            analytics = PerformanceAnalyticsService(repository),
            repository = repository,
            coordinator = ForwardTestExecutionCoordinator(
                executor = orchestrator,
                operationLog = ForwardOperationLogService(database) { Instant.EPOCH },
                clock = clock,
            ),
            scheduler = ForwardTestScheduler(context, ForwardTestSchedulerSettings(context)),
            runService = runService,
            cycleDao = database.forwardTestCycleDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            clock = clock,
            themeService = ThemeService(database.themeDao(), database.instrumentDao()),
            tradeAuditLogService = TradeAuditLogService(database.tradeAuditLogDao()),
        ).also { viewModel = it }
    }

    private fun awaitState(predicate: (ForwardTestUiState) -> Boolean): ForwardTestUiState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel!!.uiState.value
            if (predicate(state)) return state
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        fail("state condition not reached: ${viewModel!!.uiState.value}")
        throw IllegalStateException()
    }

    private suspend fun createActiveVersion(): Long {
        val strategyId = strategyService.createStrategy("VM_TEST", "VM Test")
        val draft = strategyService.createDraftVersion(strategyId)
        strategyService.upsertDraftWeight(
            strategyVersionId = draft,
            factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        assertTrue(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
        return draft
    }

    private suspend fun insertWarmupBars(count: Int) {
        var date = startDate
        var inserted = 0
        while (inserted < count) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                database.marketDailyBarDao().insert(
                    MarketDailyBarEntity(
                        instrumentId = instrumentId,
                        tradeDate = date,
                        openPrice = 50_000,
                        highPrice = 50_000,
                        lowPrice = 50_000,
                        closePrice = 50_000,
                        volume = 1_000,
                        tradingValue = null,
                        source = "TEST",
                        collectedAt = Instant.EPOCH,
                        createdAt = Instant.EPOCH,
                    ),
                )
                inserted++
            }
            date = date.minusDays(1)
        }
    }

    private fun snapshotCounts(): Map<String, Int> =
        (listOf("strategy_runs", "strategy_run_instruments", "paper_trading_policies", "cash_ledger") +
            NO_TRADING_SIDE_EFFECT_TABLES).associateWith { count(it) }

    private fun count(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private class CountingGateway : ForwardMarketDataGateway {
        @Volatile
        var calls = 0

        override suspend fun ensureCredentials(): Boolean {
            calls++
            return true
        }

        override suspend fun syncUniverseTo(
            instrumentIds: List<Long>,
            throughDate: LocalDate,
        ): MarketSyncOutcome {
            calls++
            return MarketSyncOutcome(success = true)
        }
    }

    private companion object {
        val NO_TRADING_SIDE_EFFECT_TABLES = listOf(
            "stock_evaluations",
            "orders",
            "executions",
            "positions",
            "portfolio_daily_snapshots",
            "trade_audit_logs",
            "forward_test_cycles",
        )
    }
}
