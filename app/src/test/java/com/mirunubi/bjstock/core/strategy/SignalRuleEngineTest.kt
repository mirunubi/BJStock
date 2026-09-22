package com.mirunubi.bjstock.core.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignalRuleEngineTest {
    private lateinit var database: BJStockDatabase
    private lateinit var engine: SignalRuleEngine
    private lateinit var strategyService: StrategyVersionService
    private lateinit var loader: StrategyEvaluationLoader
    private lateinit var factorValues: FactorValueRepository
    private var instrumentId = 0L
    private var factorId = 0L
    private val asOf = LocalDate.of(2026, 9, 18)
    private val prev = LocalDate.of(2026, 9, 17)
    private val next = LocalDate.of(2026, 9, 21)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = SignalRuleEngine(database.marketDailyBarDao())
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
        loader = StrategyEvaluationLoader(
            strategyService = strategyService,
            factorDao = database.factorDao(),
            factorValues = factorValues,
            signalRuleDao = database.strategySignalRuleDao(),
            signalRuleEngine = engine,
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        insertBar(prev, close = 100)
        insertBar(asOf, close = 95)
        insertBar(next, close = 200)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun dailyChangePct_isMinusFive() = runBlocking {
        val rule = rule(operator = SignalOperator.LTE, threshold = "-5.0", action = SignalAction.BUY)
        val trigger = engine.evaluateDailyChangePct(instrumentId, asOf, listOf(rule))
        assertNotNull(trigger)
        assertEquals(0, trigger!!.observedValue.compareTo(BigDecimal("-5.00000000")))
    }

    @Test
    fun buyBoundary_includesExactThreshold() = runBlocking {
        val rule = rule(operator = SignalOperator.LTE, threshold = "-5.0", action = SignalAction.BUY)
        val trigger = engine.evaluateDailyChangePct(instrumentId, asOf, listOf(rule))
        assertEquals(SignalAction.BUY, trigger!!.rule.action)
    }

    @Test
    fun sellTrigger_aboveThreePercent() = runBlocking {
        database.marketDailyBarDao().update(
            database.marketDailyBarDao().findByInstrumentAndDate(instrumentId, asOf)!!.copy(closePrice = 103),
        )
        val rule = rule(operator = SignalOperator.GTE, threshold = "3.0", action = SignalAction.SELL)
        val trigger = engine.evaluateDailyChangePct(instrumentId, asOf, listOf(rule))
        assertEquals(SignalAction.SELL, trigger!!.rule.action)
        assertTrue(trigger.observedValue >= BigDecimal("3"))
    }

    @Test
    fun noTrigger_fallsBackToFactorStrategy() = runBlocking {
        database.marketDailyBarDao().update(
            database.marketDailyBarDao().findByInstrumentAndDate(instrumentId, asOf)!!.copy(closePrice = 101),
        )
        val versionId = createActiveWithRules(
            listOf(
                Triple("BUY_RULE", SignalOperator.LTE, "-5.0") to SignalAction.BUY,
                Triple("SELL_RULE", SignalOperator.GTE, "3.0") to SignalAction.SELL,
            ),
        )
        // score 80 >= buy 70
        database.factorDao().insertValue(
            FactorValueEntity(
                instrumentId = instrumentId,
                factorId = factorId,
                evaluationDate = asOf,
                rawValue = "1",
                normalizedScore = StrategyScoreMath.scoreToStored(BigDecimal("80")),
                source = "TEST",
                calculationVersion = FactorCalculationVersions.V1,
                createdAt = Instant.EPOCH,
            ),
        )
        val version = strategyService.findStrategyVersion(versionId)!!
        val result = loader.evaluate(version, instrumentId, asOf)
        assertEquals(StrategyEvaluationStatus.SUCCESS, result.status)
        assertEquals(DecisionSource.FACTOR_STRATEGY, result.decisionSource)
        assertEquals(TradeDecision.BUY, result.quantDecision)
    }

    @Test
    fun priority_selectsLowerNumberFirst() = runBlocking {
        val sell = rule(
            code = "SELL",
            operator = SignalOperator.GTE,
            threshold = "-10.0",
            action = SignalAction.SELL,
            priority = 20,
        )
        val buy = rule(
            code = "BUY",
            operator = SignalOperator.LTE,
            threshold = "-5.0",
            action = SignalAction.BUY,
            priority = 10,
        )
        // observed -5 matches both (GTE -10 and LTE -5)
        val trigger = engine.evaluateDailyChangePct(instrumentId, asOf, listOf(sell, buy))
        assertEquals(SignalAction.BUY, trigger!!.rule.action)
        assertEquals(10, trigger.rule.priority)
    }

    @Test
    fun conflictingSamePriority_rejectedOnActivation() = runBlocking {
        val strategyId = strategyService.createStrategy("CONF", "Conflict")
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
            ruleCode = "A",
            operator = SignalOperator.LTE,
            thresholdValue = "-5",
            action = SignalAction.BUY,
            priority = 10,
        )
        strategyService.upsertDraftSignalRule(
            strategyVersionId = draft,
            ruleCode = "B",
            operator = SignalOperator.GTE,
            thresholdValue = "-10",
            action = SignalAction.SELL,
            priority = 10,
        )
        val result = strategyService.activateStrategyVersion(draft)
        assertTrue(result is StrategyActivationResult.Failed)
        assertEquals(
            StrategyActivationFailure.CONFLICTING_SIGNAL_RULES,
            (result as StrategyActivationResult.Failed).kind,
        )
    }

    @Test
    fun lookAhead_dPlusOneDoesNotAffectD() = runBlocking {
        val rule = rule(operator = SignalOperator.LTE, threshold = "-5.0", action = SignalAction.BUY)
        val before = engine.evaluateDailyChangePct(instrumentId, asOf, listOf(rule))
        database.marketDailyBarDao().update(
            database.marketDailyBarDao().findByInstrumentAndDate(instrumentId, next)!!.copy(closePrice = 1),
        )
        val after = engine.evaluateDailyChangePct(instrumentId, asOf, listOf(rule))
        assertEquals(before!!.observedValue, after!!.observedValue)
        assertEquals(before.rule.action, after.rule.action)
    }

    private fun rule(
        code: String = "R1",
        operator: SignalOperator,
        threshold: String,
        action: SignalAction,
        priority: Int = 10,
    ) = StrategySignalRuleEntity(
        id = code.hashCode().toLong().and(0x7fff),
        strategyVersionId = 1,
        ruleCode = code,
        metricCode = SignalMetricCode.DAILY_CHANGE_PCT,
        operator = operator,
        thresholdValue = threshold,
        action = action,
        priority = priority,
        enabled = true,
        createdAt = Instant.EPOCH,
    )

    private suspend fun insertBar(date: LocalDate, close: Long) {
        database.marketDailyBarDao().insert(
            MarketDailyBarEntity(
                instrumentId = instrumentId,
                tradeDate = date,
                openPrice = close,
                highPrice = close,
                lowPrice = close,
                closePrice = close,
                volume = 1,
                tradingValue = close,
                source = "KIS",
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun createActiveWithRules(
        rules: List<Pair<Triple<String, SignalOperator, String>, SignalAction>>,
    ): Long {
        val strategyId = strategyService.createStrategy("SR", "Signal Rules")
        val draft = strategyService.createDraftVersion(strategyId)
        strategyService.upsertDraftWeight(
            strategyVersionId = draft,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        rules.forEachIndexed { index, (meta, action) ->
            val (code, op, thr) = meta
            strategyService.upsertDraftSignalRule(
                strategyVersionId = draft,
                ruleCode = code,
                operator = op,
                thresholdValue = thr,
                action = action,
                priority = (index + 1) * 10,
            )
        }
        assertTrue(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
        return draft
    }
}
