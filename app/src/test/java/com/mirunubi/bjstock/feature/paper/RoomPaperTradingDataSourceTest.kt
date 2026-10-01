package com.mirunubi.bjstock.feature.paper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PositionEntity
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.forward.FakeAutoWorkGateway
import com.mirunubi.bjstock.core.forward.ForwardExecutionObserver
import com.mirunubi.bjstock.core.forward.ForwardExecutionReport
import com.mirunubi.bjstock.core.forward.ForwardRunExecutor
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestConfig
import com.mirunubi.bjstock.core.forward.ForwardTestExecutionCoordinator
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.ForwardTestSchedulerSettings
import com.mirunubi.bjstock.core.forward.RetryFailedCycleTarget
import com.mirunubi.bjstock.core.marketdata.MarketDataSource
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import com.mirunubi.bjstock.core.theme.ThemeService
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPaperTradingDataSourceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var settings: ForwardTestSchedulerSettings
    private lateinit var gateway: FakeAutoWorkGateway
    private lateinit var executor: RecordingExecutor
    private lateinit var strategyService: StrategyVersionService
    private lateinit var runService: StrategyRunService
    private lateinit var themeService: ThemeService
    private lateinit var source: RoomPaperTradingDataSource
    private var samsung = 0L
    private var hynix = 0L
    private var activeVersionId = 0L
    private var draftVersionId = 0L
    private val startDate = LocalDate.of(2026, 9, 18)
    private val now = ZonedDateTime.of(2026, 10, 1, 13, 0, 0, 0, ForwardTestConfig.MARKET_ZONE).toInstant()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = ForwardTestSchedulerSettings(context)
        settings.setAutoEnabled(false)
        gateway = FakeAutoWorkGateway()
        executor = RecordingExecutor()
        val clock = ForwardTestClock(Clock.fixed(now, ForwardTestConfig.MARKET_ZONE))
        val operationLog = ForwardOperationLogService(database) { now }
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
        themeService = ThemeService(database.themeDao(), database.instrumentDao()) { Instant.EPOCH }
        runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH },
            policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH },
            factorRegistry = registry,
            themeService = themeService,
            now = { Instant.EPOCH },
        )
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
        source = RoomPaperTradingDataSource(
            repository = repository,
            analytics = PerformanceAnalyticsService(repository),
            runService = runService,
            scheduler = ForwardTestScheduler(settings, gateway, operationLog, clock),
            coordinator = ForwardTestExecutionCoordinator(executor, operationLog, clock),
            clock = clock,
            operationDao = database.forwardOperationDao(),
            cycleDao = database.forwardTestCycleDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            themeService = themeService,
            tradeAuditLog = TradeAuditLogService(database.tradeAuditLogDao()) { Instant.EPOCH },
        )
        samsung = database.instrumentDao().insert(InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자", board = Board.KOSPI))
        hynix = database.instrumentDao().insert(InstrumentEntity(market = "KRX", symbol = "000660", name = "SK하이닉스", board = Board.KOSPI))
        val strategyId = strategyService.createStrategy("MOMENTUM_BASIC", "기본 모멘텀 전략")
        activeVersionId = strategyService.createDraftVersion(strategyId)
        strategyService.upsertDraftWeight(
            strategyVersionId = activeVersionId,
            factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id,
            weightStored = StrategyScoreMath.percentToWeightStored(BigDecimal("100")),
            enabled = true,
            factorCalculationVersion = "v1",
        )
        assertTrue(strategyService.activateStrategyVersion(activeVersionId) is StrategyActivationResult.Success)
        draftVersionId = strategyService.createDraftVersion(strategyId)
    }

    @After
    fun tearDown() {
        settings.setAutoEnabled(false)
        database.close()
    }

    @Test
    fun browsing_writesNothing_andNeverExecutesOrSchedules() = runBlocking {
        val runId = filledRun()
        val draftId = runService.createDraftRun(activeVersionId, "go hbm", startDate, 100_000_000L)
        themeService.createTheme("반도체")
        val before = rowCounts()

        source.runs()
        source.detail(runId)
        source.detail(draftId)
        source.automation()
        source.activeVersions()
        source.searchInstruments("삼성")
        source.today()

        assertEquals(before, rowCounts())
        assertEquals(0, executor.calls.size)
        assertTrue(gateway.enqueueCalls.isEmpty())
        assertEquals(0, gateway.cancelAllRequests)
        assertFalse(settings.isAutoEnabled())
    }

    @Test
    fun runs_includeEveryRun_withStrategyNameAndVersion() = runBlocking {
        val readyId = filledRun()
        val draftId = runService.createDraftRun(activeVersionId, "go hbm", startDate, 100_000_000L)

        val runs = source.runs().associateBy { it.run.id }

        assertEquals(setOf(readyId, draftId), runs.keys)
        assertEquals("기본 모멘텀 전략", runs.getValue(draftId).strategyName)
        assertEquals("V1", runs.getValue(draftId).versionLabel)
        assertEquals(RunStatus.DRAFT, runs.getValue(draftId).run.status)
        assertEquals(100_000_000L, runs.getValue(draftId).summary.initialCash)
    }

    @Test
    fun detail_readsPositionsExecutionsOrdersPolicyAndUniverse_fromTheAnalyticsLayer() = runBlocking {
        val runId = filledRun()

        val detail = source.detail(runId)!!
        val view = PaperTradingPresenter.detail(detail)

        assertEquals("삼성전자", view.holdings.single().name)
        assertEquals("37주", view.holdings.single().quantity)
        assertEquals("09.29 · 매수 · 가상 체결", view.executions.single().headline)
        assertEquals("삼성전자 37주 × 266,000원", view.executions.single().detail)
        assertEquals("수수료 1,476원", view.executions.single().costs)
        assertEquals(OrderStatus.VIRTUAL_FILLED, view.orders.single().status)
        assertEquals("다음 거래일 시가", view.policy!!.associate { it.label to it.value }["체결가격 정책"])
        assertEquals(listOf("삼성전자 005930"), view.universe.items.map { it.label })
        assertFalse(view.universe.editable)
        assertTrue(detail.themes.isEmpty())
    }

    @Test
    fun activeVersions_listOnlyActiveVersions() = runBlocking {
        val versions = source.activeVersions()

        assertEquals(listOf(activeVersionId), versions.map { it.strategyVersionId })
        assertFalse(versions.any { it.strategyVersionId == draftVersionId })
    }

    @Test
    fun draftLifecycle_goesThroughTheExistingRunService() = runBlocking {
        val themeId = themeService.createTheme("반도체")
        themeService.addInstrument(themeId, hynix)

        val runId = source.createDraftRun(activeVersionId, "반도체 모의투자", startDate, 50_000_000L)
        source.addInstrument(runId, samsung)
        assertEquals(1, source.addTheme(runId, themeId))
        source.removeInstrument(runId, hynix)
        insertWarmupBars(samsung, count = 30)
        assertEquals(0, count("paper_trading_policies"))

        source.markReady(runId)

        assertEquals(RunStatus.READY, runService.findById(runId)!!.status)
        assertEquals(1, count("paper_trading_policies"))
        assertEquals(1, count("cash_ledger"))
        val locked = assertThrows(StrategyVersionException::class.java) { runBlocking { source.addInstrument(runId, hynix) } }
        assertEquals(PaperTradingPresenter.UNIVERSE_LOCKED, PaperTradingPresenter.universeFailure(locked))
        assertEquals(0, executor.calls.size)
    }

    @Test
    fun draftCreation_rejectsANonActiveVersion() = runBlocking {
        val failure = assertThrows(StrategyVersionException::class.java) {
            runBlocking { source.createDraftRun(draftVersionId, "작성중 버전", startDate, 100_000_000L) }
        }
        assertEquals(StrategyErrorKind.VERSION_NOT_ACTIVE, failure.kind)
        assertEquals("사용 중인 전략 버전만 선택할 수 있습니다.", PaperTradingPresenter.draftFailure(failure))
        assertEquals(0, count("strategy_runs"))
    }

    @Test
    fun markReady_emptyUniverse_mapsToTheSafeMessage_andStaysDraft() = runBlocking {
        val runId = source.createDraftRun(activeVersionId, "빈 모의투자", startDate, 100_000_000L)

        val failure = assertThrows(StrategyVersionException::class.java) { runBlocking { source.markReady(runId) } }

        assertEquals("투자 대상 종목을 하나 이상 추가해 주세요.", PaperTradingPresenter.readyFailure(failure))
        assertEquals(RunStatus.DRAFT, runService.findById(runId)!!.status)
        assertEquals(0, count("cash_ledger"))
    }

    @Test
    fun explicitActions_delegateToTheCoordinatorAndScheduler() = runBlocking {
        val runId = filledRun()

        source.runNow()
        source.retryFailedCycle(runId, LocalDate.of(2026, 9, 29), 9)
        source.setAutoEnabled(true)

        assertEquals(listOf("forward", "retry"), executor.calls)
        assertEquals(RetryFailedCycleTarget(runId, LocalDate.of(2026, 9, 29), 9), executor.retryTarget)
        assertEquals(2, count("forward_operations"))
        assertTrue(settings.isAutoEnabled())
        assertEquals(1, gateway.enqueueCalls.size)
    }

    @Test
    fun noRealOrderPath_existsOrIsAdded() {
        val dependencies = RoomPaperTradingDataSource::class.java.constructors.single().parameterTypes.map { it.name }
        assertTrue(dependencies.none { it.contains(".kis.") })

        val root = File("src/main/java/com/mirunubi/bjstock")
        assertTrue(root.isDirectory)
        val tradingPathFiles = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("/trading/") }
            .map { it.name }
            .toList()
        assertEquals(listOf("KisMarketApiConfig.kt"), tradingPathFiles)
        File(root, "feature/paper").listFiles()!!.filter { it.name.startsWith("PaperTrading") }.forEach { file ->
            assertFalse(file.name, file.readText().contains("import com.mirunubi.bjstock.core.kis"))
        }
    }

    private suspend fun filledRun(): Long {
        val runId = runService.createReadyRun(activeVersionId, "모멘텀 운영", startDate, 100_000_000L, instrumentIds = listOf(samsung))
        val orderId = database.orderDao().insert(
            OrderEntity(
                clientOrderId = "paper-order-1",
                strategyRunId = runId,
                instrumentId = samsung,
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                quantity = 37,
                status = OrderStatus.VIRTUAL_FILLED,
                createdAt = ZonedDateTime.of(2026, 9, 26, 18, 30, 0, 0, ForwardTestConfig.MARKET_ZONE).toInstant(),
            ),
        )
        database.executionDao().insert(
            ExecutionEntity(
                orderId = orderId,
                executionPrice = 266_000,
                quantity = 37,
                commission = 1_476,
                executedAt = ZonedDateTime.of(2026, 9, 29, 9, 0, 0, 0, ForwardTestConfig.MARKET_ZONE).toInstant(),
                executionKey = "paper:order:$orderId:fill:1",
            ),
        )
        database.positionDao().insert(PositionEntity(strategyRunId = runId, instrumentId = samsung, quantity = 37, averagePrice = 266_000))
        insertBar(samsung, LocalDate.of(2026, 9, 30), 270_000)
        return runId
    }

    private suspend fun insertWarmupBars(instrumentId: Long, count: Int) {
        (1..count).forEach { offset -> insertBar(instrumentId, startDate.minusDays(offset.toLong()), 100_000) }
    }

    private suspend fun insertBar(instrumentId: Long, date: LocalDate, close: Long) {
        database.marketDailyBarDao().insert(
            MarketDailyBarEntity(
                instrumentId = instrumentId,
                tradeDate = date,
                openPrice = close,
                highPrice = close,
                lowPrice = close,
                closePrice = close,
                volume = 1_000L,
                source = MarketDataSource.KIS,
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private fun count(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun rowCounts(): Map<String, Int> {
        val tables = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { while (it.moveToNext()) tables += it.getString(0) }
        return tables.associateWith(::count)
    }

    private class RecordingExecutor : ForwardRunExecutor {
        val calls = mutableListOf<String>()
        var retryTarget: RetryFailedCycleTarget? = null

        override suspend fun executeForwardRuns(
            operationThroughDate: LocalDate,
            observer: ForwardExecutionObserver,
        ): ForwardExecutionReport {
            calls += "forward"
            return ForwardExecutionReport(emptyList())
        }

        override suspend fun executeRetryFailedCycle(
            target: RetryFailedCycleTarget,
            operationThroughDate: LocalDate,
            observer: ForwardExecutionObserver,
        ): ForwardExecutionReport {
            calls += "retry"
            retryTarget = target
            return ForwardExecutionReport(emptyList())
        }
    }
}
