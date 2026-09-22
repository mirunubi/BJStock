package com.mirunubi.bjstock.core.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerformanceAnalyticsServiceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var repository: PerformanceAnalyticsRepository
    private lateinit var service: PerformanceAnalyticsService
    private var versionId = 0L
    private var instrumentId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PerformanceAnalyticsRepository(
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
        service = PerformanceAnalyticsService(repository)
        val strategyId = database.strategyDao().insertStrategy(
            StrategyEntity(
                strategyCode = "PERF",
                strategyName = "Perf Strategy",
                description = null,
                isActive = true,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        versionId = database.strategyDao().insertVersion(
            StrategyVersionEntity(
                strategyId = strategyId,
                versionNo = 1,
                description = null,
                buyThreshold = 700_000,
                sellThreshold = 300_000,
                validFrom = LocalDate.of(2026, 1, 1),
                validTo = null,
                status = StrategyVersionStatus.ACTIVE,
                createdAt = Instant.EPOCH,
            ),
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyRun_isEmptyStatus() = runBlocking {
        val runId = insertRun("Empty", 100_000_000L)
        val summary = service.calculateSummary(runId)
        assertEquals(PerformanceStatus.EMPTY, summary.status)
        assertNull(summary.cumulativeReturn)
        assertNull(summary.maxDrawdown)
        assertNull(summary.winRate)
    }

    @Test
    fun positiveAndNegativeReturn_fromSnapshots() = runBlocking {
        val runId = insertRun("Pos", 100)
        insertValidSnapshot(runId, LocalDate.of(2026, 1, 2), cash = 120, market = 0)
        val pos = service.calculateSummary(runId)
        assertEquals(BigDecimal("0.20"), pos.cumulativeReturn!!.setScale(2))
        assertEquals(20L, pos.cumulativeProfit)

        val runB = insertRun("Neg", 100)
        insertValidSnapshot(runB, LocalDate.of(2026, 1, 2), cash = 80, market = 0)
        val neg = service.calculateSummary(runB)
        assertEquals(BigDecimal("-0.20"), neg.cumulativeReturn!!.setScale(2))
    }

    @Test
    fun mddAndFuturePeak_viaServiceSeries() = runBlocking {
        val runId = insertRun("MDD", 100)
        listOf(
            LocalDate.of(2026, 1, 1) to 100L,
            LocalDate.of(2026, 1, 2) to 120L,
            LocalDate.of(2026, 1, 3) to 108L,
            LocalDate.of(2026, 1, 4) to 90L,
            LocalDate.of(2026, 1, 5) to 126L,
        ).forEach { (d, a) -> insertValidSnapshot(runId, d, a, 0) }
        val summary = service.calculateSummary(runId)
        assertEquals(BigDecimal("-0.25"), summary.maxDrawdown!!.setScale(2))
        val series = service.calculateDailySeries(runId)
        assertEquals(BigDecimal("-0.25"), series[3].drawdown.setScale(2))
        val run2 = insertRun("Peak", 100)
        insertValidSnapshot(run2, LocalDate.of(2026, 2, 1), 100, 0)
        insertValidSnapshot(run2, LocalDate.of(2026, 2, 2), 90, 0)
        insertValidSnapshot(run2, LocalDate.of(2026, 2, 3), 1_000, 0)
        val s2 = service.calculateDailySeries(run2)
        assertEquals(BigDecimal("-0.10"), s2[1].drawdown.setScale(2))
    }

    @Test
    fun monthlyReturns_fromSnapshots() = runBlocking {
        val runId = insertRun("Month", 100)
        insertValidSnapshot(runId, LocalDate.of(2026, 10, 10), 103, 0)
        insertValidSnapshot(runId, LocalDate.of(2026, 10, 31), 105, 0)
        insertValidSnapshot(runId, LocalDate.of(2026, 11, 15), 102, 0)
        insertValidSnapshot(runId, LocalDate.of(2026, 12, 20), 110, 0)
        val monthly = service.calculateMonthlyReturns(runId)
        assertEquals(3, monthly.size)
        assertEquals(BigDecimal("0.05"), monthly[0].returnRate.setScale(2))
        assertEquals(BigDecimal("-0.028571428571"), monthly[1].returnRate.setScale(12))
        assertEquals(BigDecimal("0.078431372549"), monthly[2].returnRate.setScale(12))
    }

    @Test
    fun closedTrades_pairingWinRateAndCosts() = runBlocking {
        val runId = insertRun("Trades", 100_000_000L)
        insertPolicy(runId, commissionStored = 150, taxStored = 2_000)
        // BUY → SELL (+profit), BUY → SELL (-loss), open BUY
        insertFilled(runId, OrderSide.BUY, LocalDate.of(2026, 9, 1), price = 10_000, qty = 10, commission = 15)
        insertFilled(runId, OrderSide.SELL, LocalDate.of(2026, 9, 11), price = 11_000, qty = 10, commission = 17, tax = 220)
        insertFilled(runId, OrderSide.BUY, LocalDate.of(2026, 9, 12), price = 10_000, qty = 5, commission = 8)
        insertFilled(runId, OrderSide.SELL, LocalDate.of(2026, 9, 13), price = 9_000, qty = 5, commission = 7, tax = 90)
        insertFilled(runId, OrderSide.BUY, LocalDate.of(2026, 9, 14), price = 10_000, qty = 3, commission = 5)

        val paired = service.calculateClosedTrades(runId)
        assertEquals(2, paired.closed.size)
        assertEquals(1, paired.open.size)
        assertEquals(10L, paired.closed[0].holdingDays)
        val first = paired.closed[0]
        val buyCost = first.buyGross + first.buyCommission
        val sellNet = first.sellGross - first.sellCommission - first.tax
        assertEquals(sellNet - buyCost, first.netProfit)

        // Add breakeven trade on another instrument
        val inst2 = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "000660", name = "Hynix"),
        )
        insertFilled(runId, OrderSide.BUY, LocalDate.of(2026, 9, 1), 10_000, 1, 0, instrument = inst2)
        insertFilled(runId, OrderSide.SELL, LocalDate.of(2026, 9, 2), 10_000, 1, 0, 0, instrument = inst2)

        // rebuild summary win rate with 3 closed: + / - / 0
        val summary = service.calculateSummary(runId)
        assertEquals(3, summary.closedTrades)
        assertEquals(1, summary.winningTrades)
        assertEquals(1, summary.losingTrades)
        assertEquals(1, summary.breakevenTrades)
        assertEquals(BigDecimal("0.50"), summary.winRate!!.setScale(2))
        assertEquals(1, summary.openTrades)
    }

    @Test
    fun openTradeOnly_winRateNa() = runBlocking {
        val runId = insertRun("Open", 100_000_000L)
        insertFilled(runId, OrderSide.BUY, LocalDate.of(2026, 9, 1), 10_000, 10, 0)
        val summary = service.calculateSummary(runId)
        assertEquals(PerformanceStatus.EMPTY, summary.status)
        assertEquals(0, summary.closedTrades)
        assertEquals(1, summary.openTrades)
        assertNull(summary.winRate)
    }

    @Test
    fun malformedSellWithoutBuy_throws() {
        runBlocking {
            val runId = insertRun("Bad", 100_000_000L)
            insertFilled(runId, OrderSide.SELL, LocalDate.of(2026, 9, 1), 10_000, 10, 0)
            assertThrows(TradeHistoryIntegrityError::class.java) {
                runBlocking { service.calculateClosedTrades(runId) }
            }
        }
    }

    @Test
    fun analyticsDoesNotMutateDatabase() = runBlocking {
        val runId = insertRun("Mut", 100)
        insertValidSnapshot(runId, LocalDate.of(2026, 1, 2), 110, 0)
        insertPolicy(runId)
        insertFilled(runId, OrderSide.BUY, LocalDate.of(2026, 1, 3), 10_000, 1, 0)
        val before = repository.countFingerprint(runId)
        service.calculateSummary(runId)
        service.calculateDailySeries(runId)
        service.calculateMonthlyReturns(runId)
        service.calculateClosedTrades(runId)
        service.compareRuns(listOf(runId))
        val after = repository.countFingerprint(runId)
        assertEquals(before, after)
    }

    @Test
    fun comparisonAndPolicyIsolation() = runBlocking {
        val runA = insertRun("A", 100)
        val runB = insertRun("B", 200)
        insertValidSnapshot(runA, LocalDate.of(2026, 1, 2), 110, 0)
        insertValidSnapshot(runB, LocalDate.of(2026, 1, 2), 180, 0)
        insertPolicy(runA, allocationStored = 100_000, commissionStored = 150, taxStored = 2_000)
        insertPolicy(runB, allocationStored = 200_000, commissionStored = 0, taxStored = 0, version = "v1")
        val rows = service.compareRuns(listOf(runB, runA))
        assertEquals(listOf(runA, runB), rows.map { it.runId })
        assertEquals(BigDecimal("0.10"), rows[0].cumulativeReturn!!.setScale(2))
        assertEquals(BigDecimal("-0.10"), rows[1].cumulativeReturn!!.setScale(2))
        assertEquals(BigDecimal("0.10"), rows[0].buyAllocationRate!!.setScale(2))
        assertEquals(BigDecimal("0.20"), rows[1].buyAllocationRate!!.setScale(2))
        assertEquals(150L, database.paperTradingPolicyDao().findByRun(runA)!!.commissionRate)
        assertEquals(0L, database.paperTradingPolicyDao().findByRun(runB)!!.commissionRate)
    }

    private suspend fun insertRun(name: String, initial: Long): Long =
        database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = name,
                strategyVersionId = versionId,
                startDate = LocalDate.of(2026, 1, 1),
                initialCash = initial,
                status = RunStatus.RUNNING,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

    private suspend fun insertValidSnapshot(
        runId: Long,
        date: LocalDate,
        cash: Long,
        market: Long,
    ) {
        val total = cash + market
        val run = database.strategyRunDao().findById(runId)!!
        val previous = database.portfolioDailySnapshotDao().findByRun(runId)
            .filter { it.snapshotDate < date }
            .maxByOrNull { it.snapshotDate }
        val baseline = previous?.totalAsset ?: run.initialCash
        val dailyProfit = total - baseline
        val dailyReturn = CreateDailySnapshotUseCase.ratioStored(dailyProfit, baseline)
        val cumProfit = total - run.initialCash
        val cumReturn = CreateDailySnapshotUseCase.ratioStored(cumProfit, run.initialCash)
        val priorPeak = database.portfolioDailySnapshotDao()
            .findPeakTotalAsset(runId, date.minusDays(1)) ?: run.initialCash
        val peak = maxOf(priorPeak, total)
        val drawdown = CreateDailySnapshotUseCase.ratioStored(total - peak, peak)
        database.portfolioDailySnapshotDao().insert(
            PortfolioDailySnapshotEntity(
                strategyRunId = runId,
                snapshotDate = date,
                cash = cash,
                marketValue = market,
                totalAsset = total,
                dailyProfit = dailyProfit,
                dailyReturn = dailyReturn,
                cumulativeProfit = cumProfit,
                cumulativeReturn = cumReturn,
                drawdown = drawdown,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun insertPolicy(
        runId: Long,
        allocationStored: Long = 100_000,
        commissionStored: Long = 150,
        taxStored: Long = 2_000,
        version: String = "v1",
    ) {
        database.paperTradingPolicyDao().insert(
            PaperTradingPolicyEntity(
                strategyRunId = runId,
                policyVersion = version,
                buyAllocationRate = allocationStored,
                commissionRate = commissionStored,
                sellTaxRate = taxStored,
                slippageBps = 0,
                executionPricePolicy = ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN,
                additionalBuyPolicy = AdditionalBuyPolicy.DISALLOW,
                sellPolicy = SellPolicy.FULL_POSITION,
                shortSellingAllowed = false,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun insertFilled(
        runId: Long,
        side: OrderSide,
        date: LocalDate,
        price: Long,
        qty: Long,
        commission: Long,
        tax: Long = 0,
        instrument: Long = instrumentId,
    ) {
        val orderId = database.orderDao().insert(
            OrderEntity(
                clientOrderId = "c-$runId-$side-$date-${System.nanoTime()}",
                strategyRunId = runId,
                instrumentId = instrument,
                evaluationId = null,
                side = side,
                orderType = OrderType.MARKET,
                requestedPrice = null,
                quantity = qty,
                status = OrderStatus.VIRTUAL_FILLED,
                createdAt = Instant.EPOCH,
                executedAt = MarketExecutionTime.of(date),
            ),
        )
        database.executionDao().insert(
            ExecutionEntity(
                orderId = orderId,
                executionPrice = price,
                quantity = qty,
                commission = commission,
                tax = tax,
                slippage = 0,
                executedAt = MarketExecutionTime.of(date),
                createdAt = Instant.EPOCH,
            ),
        )
    }
}
