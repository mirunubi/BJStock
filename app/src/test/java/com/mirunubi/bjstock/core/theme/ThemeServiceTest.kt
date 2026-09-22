package com.mirunubi.bjstock.core.theme

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import java.time.Instant
import java.time.LocalDate
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
class ThemeServiceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var themes: ThemeService
    private lateinit var runService: StrategyRunService
    private lateinit var strategyService: StrategyVersionService
    private var instrumentA = 0L
    private var instrumentB = 0L
    private var instrumentC = 0L
    private var versionId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        themes = ThemeService(database.themeDao(), database.instrumentDao()) { Instant.EPOCH }
        val factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        factorValues.ensureSystemFactorDefinitions()
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
            themeService = themes,
            defaultPolicyTemplate = { PaperTradingPolicy.ZERO_COST },
            now = { Instant.EPOCH },
        )
        instrumentA = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "000660", name = "SK Hynix"),
        )
        instrumentB = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        instrumentC = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "035420", name = "NAVER"),
        )
        versionId = createActiveVersion(factorValues)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createTheme_andRejectDuplicateName() = runBlocking<Unit> {
        val id = themes.createTheme("HBM관련")
        assertTrue(id > 0)
        assertThrows(ThemeException::class.java) {
            runBlocking { themes.createTheme(" HBM관련 ") }
        }
    }

    @Test
    fun multiMembership_andRemove_andInactive() = runBlocking<Unit> {
        val hbm = themes.createTheme("HBM관련")
        val semi = themes.createTheme("반도체")
        themes.addInstrument(hbm, instrumentA)
        themes.addInstrument(hbm, instrumentB)
        themes.addInstrument(semi, instrumentA)
        assertEquals(2, themes.countInstruments(hbm))
        assertEquals(listOf(instrumentA), themes.listInstrumentIds(semi))
        assertThrows(ThemeException::class.java) {
            runBlocking { themes.addInstrument(hbm, instrumentA) }
        }
        themes.removeInstrument(hbm, instrumentB)
        assertEquals(listOf(instrumentA), themes.listInstrumentIds(hbm))
        themes.setActive(hbm, false)
        assertFalse(themes.findById(hbm)!!.isActive)
    }

    @Test
    fun themeToUniverseCopy_isImmutableAfterThemeChange() = runBlocking<Unit> {
        val themeId = themes.createTheme("HBM관련")
        themes.addInstrument(themeId, instrumentA)
        themes.addInstrument(themeId, instrumentB)
        themes.addInstrument(themeId, instrumentC)
        val runId = runService.createDraftRun(
            strategyVersionId = versionId,
            runName = "Theme Copy",
            startDate = LocalDate.of(2026, 9, 18),
            initialCashWon = 10_000_000L,
        )
        val added = runService.addThemeToUniverse(runId, themeId)
        assertEquals(3, added)
        assertEquals(3, runService.listUniverse(runId).size)

        themes.removeInstrument(themeId, instrumentC)
        assertEquals(2, themes.countInstruments(themeId))
        assertEquals(3, runService.listUniverse(runId).size)

        database.strategyRunDao().updateStatus(runId, RunStatus.READY, Instant.EPOCH)
        assertEquals(RunStatus.READY, runService.findById(runId)!!.status)
        assertThrows(Exception::class.java) {
            runBlocking { runService.addThemeToUniverse(runId, themeId) }
        }
        assertEquals(3, runService.listUniverse(runId).size)
    }

    private suspend fun createActiveVersion(factorValues: FactorValueRepository): Long {
        val strategyId = strategyService.createStrategy("THEME", "Theme Strategy")
        val draft = strategyService.createDraftVersion(strategyId)
        val factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id
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
}
