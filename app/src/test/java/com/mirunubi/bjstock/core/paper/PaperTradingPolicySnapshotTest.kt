package com.mirunubi.bjstock.core.paper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaperTradingPolicySnapshotTest {
    private lateinit var database: BJStockDatabase
    private lateinit var cashLedger: CashLedgerService
    private lateinit var policyService: PaperTradingPolicyService
    private lateinit var strategyService: StrategyVersionService
    private lateinit var runService: StrategyRunService
    private lateinit var processEvaluation: ProcessEvaluationUseCase
    private lateinit var processPending: ProcessPendingOrdersUseCase
    private var versionId = 0L
    private var instrumentId = 0L
    private val friday = LocalDate.of(2026, 9, 18)
    private val monday = LocalDate.of(2026, 9, 21)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH }
        val factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        factorValues.ensureSystemFactorDefinitions()
        strategyService = StrategyVersionService(
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = SystemFactorRegistryFactory.create(),
            now = { Instant.EPOCH },
        )
        runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            cashLedger = cashLedger,
            policyService = policyService,
            defaultPolicyTemplate = { PaperTradingPolicy.DEFAULT },
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
            fills = VirtualFillService(
                database = database,
                orderDao = database.orderDao(),
                executionDao = database.executionDao(),
                positionDao = database.positionDao(),
                cashLedger = cashLedger,
                now = { Instant.EPOCH },
            ),
            policyService = policyService,
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
    fun policyCreation_andDuplicateRejected() {
        runBlocking {
            val runId = runService.createReadyRun(versionId, "A", friday, 100_000_000L)
            val policy = policyService.findByRun(runId)
            assertNotNull(policy)
            assertEquals("v1", policy!!.policyVersion)
            assertEquals(100_000L, policy.buyAllocationRate)
            assertEquals(150L, policy.commissionRate)
            assertEquals(2_000L, policy.sellTaxRate)
            assertEquals(0L, policy.slippageBps)
            assertThrows(DuplicateTradingPolicyException::class.java) {
                runBlocking { policyService.createSnapshot(runId) }
            }
        }
    }

    @Test
    fun missingPolicy_blocksTrading() = runBlocking {
        val runId = database.strategyRunDao().insert(
            com.mirunubi.bjstock.core.database.entity.StrategyRunEntity(
                runName = "NoPolicy",
                strategyVersionId = versionId,
                startDate = friday,
                initialCash = 100_000_000L,
                status = com.mirunubi.bjstock.core.model.RunStatus.READY,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        cashLedger.appendInitialDeposit(runId, 100_000_000L, friday)
        val result = processPending(runId).single()
        assertEquals(PaperTradeAction.MISSING_TRADING_POLICY, result.action)
    }

    @Test
    fun oldRunKeepsSnapshot_afterDefaultTemplateChanges() = runBlocking {
        val runA = runService.createReadyRun(
            strategyVersionId = versionId,
            runName = "A",
            startDate = friday,
            initialCashWon = 100_000_000L,
            policyTemplate = PaperTradingPolicy.DEFAULT,
        )
        // Simulate later code default change for NEW runs only.
        val changedTemplate = PaperTradingPolicy(
            buyAllocationRate = BigDecimal("0.20"),
            costPolicy = TradingCostPolicy.ZERO,
        )
        val runServiceB = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            cashLedger = cashLedger,
            policyService = policyService,
            defaultPolicyTemplate = { changedTemplate },
            now = { Instant.EPOCH },
        )
        val runB = runServiceB.createReadyRun(versionId, "B", friday, 100_000_000L)

        val snapA = policyService.requireByRun(runA)
        val snapB = policyService.requireByRun(runB)
        assertEquals(100_000L, snapA.buyAllocationRate)
        assertEquals(150L, snapA.commissionRate)
        assertEquals(2_000L, snapA.sellTaxRate)
        assertEquals(200_000L, snapB.buyAllocationRate)
        assertEquals(0L, snapB.commissionRate)
        assertEquals(0L, snapB.sellTaxRate)

        insertBar(instrumentId, monday, 50_000, 51_000)
        val evalA = insertEvaluation(runA, friday, TradeDecision.BUY)
        processEvaluation(evalA)
        processPending(runA)
        // 10% allocation from snapshot A, with baseline commission → affordable qty.
        val qtyA = database.positionDao().find(runA, instrumentId)!!.quantity
        assertTrue(qtyA in 198L..200L)

        val evalB = insertEvaluation(runB, friday, TradeDecision.BUY)
        processEvaluation(evalB)
        processPending(runB)
        // 20% allocation zero cost → exactly 400 shares.
        assertEquals(400L, database.positionDao().find(runB, instrumentId)!!.quantity)
    }

    @Test
    fun buyAndSellCosts_areDeterministicFromSnapshot() = runBlocking {
        val runtime = policyService.toRuntime(
            com.mirunubi.bjstock.core.database.entity.PaperTradingPolicyEntity(
                strategyRunId = 1,
                policyVersion = "v1",
                buyAllocationRate = 100_000,
                commissionRate = 150,
                sellTaxRate = 2_000,
                slippageBps = 0,
                executionPricePolicy = com.mirunubi.bjstock.core.model.ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN,
                additionalBuyPolicy = com.mirunubi.bjstock.core.model.AdditionalBuyPolicy.DISALLOW,
                sellPolicy = com.mirunubi.bjstock.core.model.SellPolicy.FULL_POSITION,
                shortSellingAllowed = false,
            ),
        )
        val gross = 50_000L * 200L
        val c1 = runtime.costPolicy.commission(gross)
        val c2 = runtime.costPolicy.commission(gross)
        val t1 = runtime.costPolicy.sellTax(gross)
        val t2 = runtime.costPolicy.sellTax(gross)
        assertEquals(c1, c2)
        assertEquals(t1, t2)
        assertEquals(1_500L, c1)
        assertEquals(20_000L, t1)
    }

    @Test
    fun marketDateConversion_isTimezoneStable() {
        val previous = TimeZone.getDefault()
        try {
            listOf("Asia/Seoul", "America/Los_Angeles", "UTC").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val date = LocalDate.of(2026, 9, 22)
                val instant = MarketExecutionTime.of(date)
                assertEquals(Instant.parse("2026-09-22T00:00:00Z"), instant)
                assertEquals(date, MarketExecutionTime.toTradeDate(instant))
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun initializationIsAtomic_withPolicyAndDeposit() = runBlocking {
        val runId = runService.createReadyRun(versionId, "Atomic", friday, 50_000_000L)
        assertNotNull(policyService.findByRun(runId))
        assertEquals(50_000_000L, cashLedger.currentCash(runId))
        assertEquals(1, database.cashLedgerDao().countByRun(runId))
        assertEquals(
            com.mirunubi.bjstock.core.model.RunStatus.READY,
            runService.findById(runId)!!.status,
        )
    }

    @Test
    fun rateScale_usesExistingWeightFactor() {
        assertEquals(
            100_000L,
            PaperTradingPolicyService.rateToStored(BigDecimal("0.10")),
        )
        assertEquals(
            150L,
            PaperTradingPolicyService.rateToStored(BigDecimal("0.00015")),
        )
        assertEquals(NumericMapping.WEIGHT_FACTOR, 1_000_000L)
    }

    @Test
    fun policyIsImmutable_noUpdatePathAfterSnapshot() {
        runBlocking {
            val runId = runService.createReadyRun(versionId, "Immutable", friday, 10_000_000L)
            val before = policyService.requireByRun(runId)
            // There is no update API; a second create must fail and leave the row unchanged.
            assertThrows(DuplicateTradingPolicyException::class.java) {
                runBlocking {
                    policyService.createSnapshot(
                        strategyRunId = runId,
                        template = PaperTradingPolicy(
                            buyAllocationRate = BigDecimal("0.20"),
                            costPolicy = TradingCostPolicy.ZERO,
                        ),
                    )
                }
            }
            val after = policyService.requireByRun(runId)
            assertEquals(before.id, after.id)
            assertEquals(before.buyAllocationRate, after.buyAllocationRate)
            assertEquals(before.commissionRate, after.commissionRate)
            assertEquals(before.sellTaxRate, after.sellTaxRate)
        }
    }

    private suspend fun createActiveVersion(): Long {
        val strategyId = strategyService.createStrategy("POLICY_TEST", "Policy Test")
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

    private suspend fun insertEvaluation(
        runId: Long,
        date: LocalDate,
        decision: TradeDecision,
    ): Long = database.stockEvaluationDao().insertEvaluation(
        StockEvaluationEntity(
            strategyRunId = runId,
            instrumentId = instrumentId,
            evaluationDate = date,
            quantScore = 800_000L,
            finalScore = 800_000L,
            quantDecision = decision,
            finalDecision = decision,
            createdAt = Instant.EPOCH,
        ),
    )

    private suspend fun insertBar(instrumentId: Long, date: LocalDate, open: Long, close: Long) {
        database.marketDailyBarDao().insert(
            MarketDailyBarEntity(
                instrumentId = instrumentId,
                tradeDate = date,
                openPrice = open,
                highPrice = maxOf(open, close),
                lowPrice = minOf(open, close),
                closePrice = close,
                volume = 1,
                source = "TEST",
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }
}
