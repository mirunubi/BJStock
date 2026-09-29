package com.mirunubi.bjstock.core.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StrategyRunDraftLifecycleTest {
    private lateinit var database: BJStockDatabase
    private lateinit var runService: StrategyRunService
    private lateinit var strategyService: StrategyVersionService
    private lateinit var factorValues: FactorValueRepository
    private var instrumentId = 0L
    private var activeVersionId = 0L
    private val startDate = LocalDate.of(2026, 9, 28)

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
            policyService = PaperTradingPolicyService(
                policyDao = database.paperTradingPolicyDao(),
                now = { Instant.EPOCH },
            ),
            factorRegistry = SystemFactorRegistryFactory.create(),
            defaultPolicyTemplate = { PaperTradingPolicy.ZERO_COST },
            now = { Instant.EPOCH },
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        activeVersionId = createVersion(activate = true)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createDraftRun_persistsDraftRowOnly() = runBlocking<Unit> {
        val runId = runService.createDraftRun(
            strategyVersionId = activeVersionId,
            runName = " Runtime Acceptance Run ",
            startDate = startDate,
            initialCashWon = 100_000_000L,
        )

        val run = runService.findById(runId)!!
        assertEquals(RunStatus.DRAFT, run.status)
        assertEquals(activeVersionId, run.strategyVersionId)
        assertEquals(100_000_000L, run.initialCash)
        assertEquals(startDate, run.startDate)
        assertEquals("Runtime Acceptance Run", run.runName)
        assertEquals(1, count("strategy_runs"))
        SIDE_EFFECT_TABLES.forEach { table -> assertEquals(table, 0, count(table)) }
    }

    @Test
    fun createDraftRun_rejectsNonActiveVersion_nonPositiveCash_blankName() = runBlocking<Unit> {
        val draftVersionId = createVersion(activate = false)
        assertThrows(StrategyVersionException::class.java) {
            runBlocking {
                runService.createDraftRun(draftVersionId, "Run", startDate, 100_000_000L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runService.createDraftRun(activeVersionId, "Run", startDate, 0L) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runService.createDraftRun(activeVersionId, "  ", startDate, 1L) }
        }
        assertEquals(0, count("strategy_runs"))
    }

    @Test
    fun listActiveVersions_returnsOnlyActive() = runBlocking<Unit> {
        createVersion(activate = false)
        val versions = runService.listActiveVersions()
        assertEquals(listOf(activeVersionId), versions.map { it.strategyVersionId })
    }

    @Test
    fun markReady_createsPolicyAndInitialDepositAtomically() = runBlocking<Unit> {
        val runId = runService.createDraftRun(activeVersionId, "Run", startDate, 100_000_000L)
        runService.addInstrument(runId, instrumentId)
        insertWarmupBars(count = 30)

        runService.markReady(runId)

        assertEquals(RunStatus.READY, runService.findById(runId)!!.status)
        assertEquals(1, database.paperTradingPolicyDao().countByRun(runId))
        val ledger = database.cashLedgerDao().findByRun(runId)
        assertEquals(1, ledger.size)
        assertEquals(CashLedgerEventType.INITIAL_DEPOSIT, ledger.single().eventType)
        assertEquals(100_000_000L, ledger.single().amount)
        assertEquals(startDate, ledger.single().eventDate)
        listOf("orders", "executions", "stock_evaluations", "portfolio_daily_snapshots")
            .forEach { table -> assertEquals(table, 0, count(table)) }

        assertThrows(StrategyVersionException::class.java) {
            runBlocking { runService.markReady(runId) }
        }
        assertEquals(1, database.cashLedgerDao().findByRun(runId).size)
        assertEquals(1, database.paperTradingPolicyDao().countByRun(runId))
    }

    @Test
    fun markReady_validationFailure_leavesDraftWithoutFunding() = runBlocking<Unit> {
        val runId = runService.createDraftRun(activeVersionId, "Run", startDate, 100_000_000L)

        val emptyUniverse = assertThrows(StrategyVersionException::class.java) {
            runBlocking { runService.markReady(runId) }
        }
        assertTrue(emptyUniverse.message.contains("EMPTY_UNIVERSE"))

        runService.addInstrument(runId, instrumentId)
        val warmup = assertThrows(StrategyVersionException::class.java) {
            runBlocking { runService.markReady(runId) }
        }
        assertTrue(warmup.message.contains("INSUFFICIENT_WARMUP_DATA"))

        assertEquals(RunStatus.DRAFT, runService.findById(runId)!!.status)
        assertEquals(0, count("cash_ledger"))
        assertEquals(0, count("paper_trading_policies"))
    }

    private suspend fun createVersion(activate: Boolean): Long {
        val strategyId = strategyService.createStrategy(
            "S${System.nanoTime()}",
            "Strategy ${System.nanoTime()}",
        )
        val draft = strategyService.createDraftVersion(strategyId)
        strategyService.upsertDraftWeight(
            strategyVersionId = draft,
            factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        if (activate) {
            assertTrue(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
        }
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

    private fun count(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        val SIDE_EFFECT_TABLES = listOf(
            "strategy_run_instruments",
            "cash_ledger",
            "paper_trading_policies",
            "stock_evaluations",
            "stock_evaluation_details",
            "orders",
            "executions",
            "positions",
            "portfolio_daily_snapshots",
            "trade_audit_logs",
            "forward_test_cycles",
        )
    }
}
