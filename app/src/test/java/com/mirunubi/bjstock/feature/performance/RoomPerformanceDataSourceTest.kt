package com.mirunubi.bjstock.feature.performance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PaperTradingPolicyEntity
import com.mirunubi.bjstock.core.database.entity.PortfolioDailySnapshotEntity
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.model.AdditionalBuyPolicy
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.SellPolicy
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.paper.CreateDailySnapshotUseCase
import com.mirunubi.bjstock.core.paper.MarketExecutionTime
import com.mirunubi.bjstock.core.paper.VirtualFillService
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPerformanceDataSourceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var source: RoomPerformanceDataSource
    private var versionId = 0L
    private var instrumentId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        source = RoomPerformanceDataSource(repository, PerformanceAnalyticsService(repository))
        val strategyId = database.strategyDao().insertStrategy(
            StrategyEntity(
                strategyCode = "MOMENTUM_BASIC",
                strategyName = "기본 모멘텀 전략",
                description = null,
                isActive = true,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        versionId = database.strategyDao().insertVersion(
            StrategyVersionEntity(
                strategyId = strategyId,
                versionNo = 2,
                description = null,
                buyThreshold = 700_000,
                sellThreshold = 300_000,
                validFrom = LocalDate.of(2026, 1, 1),
                validTo = null,
                status = StrategyVersionStatus.ACTIVE,
                createdAt = Instant.EPOCH,
            ),
        )
        instrumentId = database.instrumentDao().insert(InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun browsingAndComparing_leaveEveryTableUnchanged() = runBlocking {
        val running = measuredRun()
        val draft = insertRun("go hbm", RunStatus.DRAFT)
        val before = rowCounts()

        source.runs()
        source.detail(running)
        source.detail(draft)
        source.compare(listOf(running, draft))

        assertEquals(before, rowCounts())
    }

    @Test
    fun detail_readsSummarySeriesAndMonthly_fromTheAnalyticsService() = runBlocking {
        val runId = measuredRun()

        val detail = source.detail(runId)!!
        val view = PerformancePresenter.detail(detail) as PerformanceDetailView.Ready

        assertEquals(PerformanceStatus.IN_PROGRESS, detail.run.summary.status)
        assertEquals("기본 모멘텀 전략", detail.run.strategyName)
        assertEquals("V2", detail.run.versionLabel)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)),
            detail.series.map { it.date },
        )
        assertEquals(listOf(9, 10), detail.monthly.map { it.month })
        val metrics = view.metrics as KeyMetricsView.Measured
        assertEquals("101,000,000원", metrics.totalAsset)
        assertEquals("+1,000,000원", metrics.cumulativeProfit)
        assertEquals("+1.00%", metrics.cumulativeReturn)
        assertEquals("-1.98%", metrics.maxDrawdown)
        assertEquals("계산 불가", metrics.cagr)
        assertEquals("2026.09.18 ~ 2026.10.01", view.header.period)
        assertEquals("3일", view.header.tradingDays)
        assertEquals(3, view.equity!!.points.size)
        assertEquals("1건", view.signals.executions.single { it.label == "매수 체결" }.value)
    }

    @Test
    fun corruptedSnapshots_areDataError_withoutThrowing() = runBlocking {
        val runId = insertRun("검증 실패", RunStatus.RUNNING)
        insertSnapshot(runId, LocalDate.of(2026, 9, 29), cash = 100_000_000, market = 0, totalOverride = 100_000_001)

        val detail = source.detail(runId)!!

        assertEquals(PerformanceStatus.DATA_ERROR, detail.run.summary.status)
        assertTrue(detail.series.isEmpty())
        val view = PerformancePresenter.detail(detail) as PerformanceDetailView.DataError
        assertFalse(view.toString().contains("cash + market_value"))
    }

    @Test
    fun compare_usesTheExistingCompareRuns_withPolicyContext() = runBlocking {
        val running = measuredRun()
        val draft = insertRun("go hbm", RunStatus.DRAFT)

        val rows = source.compare(listOf(draft, running))

        assertEquals(listOf(running, draft), rows.map { it.runId })
        val view = PerformancePresenter.comparison(rows, PerformancePresenter.runRows(source.runs()).associateBy { it.runId })
        assertEquals("10%", view.columns[0].policy.single { it.label == "1회 매수 비중" }.value)
        assertEquals("0.015%", view.columns[0].policy.single { it.label == "수수료 가정" }.value)
        assertEquals("0.2%", view.columns[0].policy.single { it.label == "매도세 가정" }.value)
        assertEquals("운영 중", view.columns[0].badge!!.label)
        assertEquals("운영 준비 전이라 아직 없음", view.columns[1].policy.single().value)
    }

    @Test
    fun performanceFeature_hasNoKisSchedulerOrExecutionDependency() {
        val dependencies = RoomPerformanceDataSource::class.java.constructors.single().parameterTypes.toList()
        assertEquals(listOf(PerformanceAnalyticsRepository::class.java, PerformanceAnalyticsService::class.java), dependencies)

        val dir = File("src/main/java/com/mirunubi/bjstock/feature/performance")
        assertTrue(dir.isDirectory)
        val banned = listOf("core.kis", "core.forward", "core.paper", "/trading/", "WorkManager", "Coordinator", "Scheduler")
        dir.listFiles()!!.filter { it.name.startsWith("Performance") }.forEach { file ->
            val text = file.readText()
            banned.forEach { assertFalse("${file.name} must not reference $it", text.contains(it)) }
        }
    }

    // region Seeding

    /** RUNNING Run with a policy, one virtual BUY fill and three valid snapshots (peak 101,000,000 → 99,000,000 → 101,000,000). */
    private suspend fun measuredRun(): Long {
        val runId = insertRun("모멘텀 운영", RunStatus.RUNNING)
        insertPolicy(runId)
        insertFilledBuy(runId, LocalDate.of(2026, 9, 29))
        insertSnapshot(runId, LocalDate.of(2026, 9, 29), cash = 101_000_000, market = 0)
        insertSnapshot(runId, LocalDate.of(2026, 9, 30), cash = 99_000_000, market = 0)
        insertSnapshot(runId, LocalDate.of(2026, 10, 1), cash = 101_000_000, market = 0)
        return runId
    }

    private suspend fun insertRun(name: String, status: RunStatus): Long =
        database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = name,
                strategyVersionId = versionId,
                startDate = LocalDate.of(2026, 9, 18),
                initialCash = 100_000_000,
                status = status,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

    private suspend fun insertSnapshot(runId: Long, date: LocalDate, cash: Long, market: Long, totalOverride: Long? = null) {
        val total = totalOverride ?: (cash + market)
        val run = database.strategyRunDao().findById(runId)!!
        val previous = database.portfolioDailySnapshotDao().findByRun(runId).filter { it.snapshotDate < date }.maxByOrNull { it.snapshotDate }
        val baseline = previous?.totalAsset ?: run.initialCash
        val peak = maxOf(database.portfolioDailySnapshotDao().findPeakTotalAsset(runId, date.minusDays(1)) ?: run.initialCash, total)
        database.portfolioDailySnapshotDao().insert(
            PortfolioDailySnapshotEntity(
                strategyRunId = runId,
                snapshotDate = date,
                cash = cash,
                marketValue = market,
                totalAsset = total,
                dailyProfit = total - baseline,
                dailyReturn = CreateDailySnapshotUseCase.ratioStored(total - baseline, baseline),
                cumulativeProfit = total - run.initialCash,
                cumulativeReturn = CreateDailySnapshotUseCase.ratioStored(total - run.initialCash, run.initialCash),
                drawdown = CreateDailySnapshotUseCase.ratioStored(total - peak, peak),
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun insertPolicy(runId: Long) {
        database.paperTradingPolicyDao().insert(
            PaperTradingPolicyEntity(
                strategyRunId = runId,
                policyVersion = "v1",
                buyAllocationRate = 100_000,
                commissionRate = 150,
                sellTaxRate = 2_000,
                slippageBps = 0,
                executionPricePolicy = ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN,
                additionalBuyPolicy = AdditionalBuyPolicy.DISALLOW,
                sellPolicy = SellPolicy.FULL_POSITION,
                shortSellingAllowed = false,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun insertFilledBuy(runId: Long, date: LocalDate) {
        val orderId = database.orderDao().insert(
            OrderEntity(
                clientOrderId = "paper-$runId-$date",
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationId = null,
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                requestedPrice = null,
                quantity = 1,
                status = OrderStatus.VIRTUAL_FILLED,
                createdAt = Instant.EPOCH,
                executedAt = MarketExecutionTime.of(date),
            ),
        )
        database.executionDao().insert(
            ExecutionEntity(
                orderId = orderId,
                executionPrice = 10_000,
                quantity = 1,
                commission = 0,
                tax = 0,
                slippage = 0,
                executedAt = MarketExecutionTime.of(date),
                createdAt = Instant.EPOCH,
                executionKey = VirtualFillService.executionKey(orderId),
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

    // endregion
}
