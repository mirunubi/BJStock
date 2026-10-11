package com.mirunubi.bjstock.feature.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.feature.admin.AdminAuditData
import com.mirunubi.bjstock.feature.admin.AdminDataSource
import com.mirunubi.bjstock.feature.admin.AdminEnvironmentData
import com.mirunubi.bjstock.feature.admin.AdminErrorData
import com.mirunubi.bjstock.feature.admin.AdminOperationDetailData
import com.mirunubi.bjstock.feature.admin.AdminStatusData
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomHomeActivitySourceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var source: RoomHomeActivitySource
    private val admin = RecordingAdminDataSource()
    private var running = 0L
    private var ready = 0L
    private var samsung = 0L
    private val day1 = LocalDate.of(2026, 9, 26)
    private val day2 = LocalDate.of(2026, 9, 29)
    private val t0 = Instant.parse("2026-09-29T22:00:00Z")

    private val watchedTables = listOf(
        "strategy_runs",
        "strategy_versions",
        "orders",
        "executions",
        "stock_evaluations",
        "trade_audit_logs",
        "instruments",
        "forward_operations",
        "operational_events",
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val strategyId = database.strategyDao().insertStrategy(StrategyEntity(strategyCode = "MOMENTUM", strategyName = "Momentum"))
        val version = database.strategyDao().insertVersion(
            StrategyVersionEntity(strategyId = strategyId, versionNo = 2, buyThreshold = 1, sellThreshold = 0, status = StrategyVersionStatus.ACTIVE),
        )
        fun run(name: String, status: RunStatus) =
            StrategyRunEntity(runName = name, strategyVersionId = version, startDate = day1, initialCash = 10_000_000, status = status)
        val runs = database.strategyRunDao()
        ready = runs.insert(run("ready", RunStatus.READY))
        running = runs.insert(run("running", RunStatus.RUNNING))
        runs.insert(run("draft", RunStatus.DRAFT))
        val completed = runs.insert(run("done", RunStatus.COMPLETED))
        samsung = database.instrumentDao().insert(InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자"))

        val evaluations = database.stockEvaluationDao()
        fun evaluation(date: LocalDate, decision: TradeDecision) = StockEvaluationEntity(
            strategyRunId = running,
            instrumentId = samsung,
            evaluationDate = date,
            quantScore = 1,
            finalScore = 1,
            quantDecision = decision,
            finalDecision = decision,
        )
        val oldEval = evaluations.insertEvaluation(evaluation(day1, TradeDecision.HOLD))
        val newEval = evaluations.insertEvaluation(evaluation(day2, TradeDecision.BUY))
        val audits = database.tradeAuditLogDao()
        fun audit(key: String, type: TradeAuditEventType, evaluationId: Long?, date: LocalDate?, at: Instant) = TradeAuditLogEntity(
            strategyRunId = running,
            instrumentId = samsung,
            evaluationId = evaluationId,
            marketDate = date,
            eventType = type,
            eventKey = key,
            createdAt = at,
        )
        audits.insert(audit("old", TradeAuditEventType.EVALUATION_DECIDED, oldEval, day1, t0.minusSeconds(86_400)))
        audits.insert(audit("rule", TradeAuditEventType.RULE_TRIGGERED, newEval, day2, t0))
        audits.insert(audit("new", TradeAuditEventType.EVALUATION_DECIDED, newEval, day2, t0.plusSeconds(1)))
        audits.insert(audit("order", TradeAuditEventType.ORDER_CREATED, newEval, day2, t0.plusSeconds(2)))

        val orders = database.orderDao()
        fun order(key: String, runId: Long, at: Instant, status: OrderStatus) = OrderEntity(
            clientOrderId = key,
            strategyRunId = runId,
            instrumentId = samsung,
            side = OrderSide.BUY,
            orderType = OrderType.MARKET,
            quantity = 10,
            status = status,
            createdAt = at,
        )
        val filled = orders.insert(order("o1", completed, t0.minusSeconds(300), OrderStatus.VIRTUAL_FILLED))
        orders.insert(order("o2", running, t0.minusSeconds(200), OrderStatus.PENDING_EXECUTION))
        orders.insert(order("o3", running, t0.minusSeconds(100), OrderStatus.PENDING_EXECUTION))
        orders.insert(order("o4", running, t0.minusSeconds(900), OrderStatus.CANCELLED))
        database.executionDao().insert(
            ExecutionEntity(orderId = filled, executionPrice = 271_000, quantity = 10, executedAt = t0, executionKey = "paper:order:$filled:fill:1"),
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
        source = RoomHomeActivitySource(
            repository = repository,
            admin = admin,
            auditDao = database.tradeAuditLogDao(),
            evaluationDao = database.stockEvaluationDao(),
            instrumentDao = database.instrumentDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun counts(): Map<String, Long> = watchedTables.associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    @Test
    fun activeRuns_areReadyAndRunningOnly_runningFirst() = runBlocking {
        val runs = source.activeRuns()

        assertEquals(listOf(running, ready), runs.map { it.runId })
        assertEquals(listOf(RunStatus.RUNNING, RunStatus.READY), runs.map { it.status })
        assertEquals("Momentum", runs.first().strategyName)
        assertEquals("V2", runs.first().versionLabel)
        assertEquals(10_000_000L, runs.first().initialCash)
    }

    @Test
    fun signals_comeFromTheLatestProcessedMarketDateOnly() = runBlocking {
        val data = source.signals()

        assertEquals(day2, data.marketDate)
        assertEquals(listOf(TradeAuditEventType.EVALUATION_DECIDED, TradeAuditEventType.RULE_TRIGGERED), data.logs.map { it.eventType })
        assertEquals(listOf(TradeDecision.BUY), data.decisions.values.toList())
        assertEquals("삼성전자", data.instruments[samsung]?.name)
        assertEquals("running", data.runs[running]?.runName)
    }

    @Test
    fun trades_areNewestFirstAcrossRuns_withExecutionAttached() = runBlocking {
        val data = source.trades()

        assertEquals(listOf("o3", "o2", "o1"), data.orders.map { it.clientOrderId })
        assertEquals(4, data.totalOrders)
        assertEquals(271_000L, data.executions[data.orders[2].id]?.executionPrice)
        assertEquals(1, data.executions.size)
        assertEquals("005930", data.instruments[samsung]?.symbol)
    }

    @Test
    fun errorsAndAudit_reuseTheAdminReadModel() = runBlocking {
        source.errors()
        source.audit()
        assertEquals(listOf("errors", "audit"), admin.calls)
    }

    @Test
    fun loadingEverySection_writesNothing() = runBlocking {
        val before = counts()

        source.activeRuns()
        source.signals()
        source.trades()
        source.errors()
        source.audit()

        assertEquals(before, counts())
    }

    class RecordingAdminDataSource : AdminDataSource {
        val calls = mutableListOf<String>()

        override suspend fun errors(): AdminErrorData {
            calls += "errors"
            return AdminErrorData(emptyList(), emptyList(), emptyList(), emptyList())
        }

        override suspend fun audit(): AdminAuditData {
            calls += "audit"
            return AdminAuditData(emptyList(), emptyList(), emptyList(), emptyList())
        }

        override suspend fun status(): AdminStatusData = error("not used by Home")

        override suspend fun recentOperations(): List<ForwardOperationEntity> = error("not used by Home")

        override suspend fun operationDetail(operationId: Long): AdminOperationDetailData? = error("not used by Home")

        override suspend fun environment(): AdminEnvironmentData = error("not used by Home")
    }
}
