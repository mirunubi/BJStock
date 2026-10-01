package com.mirunubi.bjstock.feature.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.marketdata.MarketDataSource
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.strategy.PreviewStrategyEvaluationUseCase
import com.mirunubi.bjstock.core.strategy.SignalRuleEngine
import com.mirunubi.bjstock.core.strategy.StrategyActivationFailure
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationLoader
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
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
class RoomStrategyDataSourceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var factorValues: FactorValueRepository
    private lateinit var service: StrategyVersionService
    private lateinit var source: RoomStrategyDataSource
    private var samsung = 0L
    private val day1 = LocalDate.of(2026, 9, 28)
    private val day2 = LocalDate.of(2026, 9, 29)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        factorValues.ensureSystemFactorDefinitions()
        val registry = SystemFactorRegistryFactory.create()
        service = StrategyVersionService(
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = registry,
            signalRuleDao = database.strategySignalRuleDao(),
            now = { Instant.EPOCH },
        )
        val loader = StrategyEvaluationLoader(
            strategyService = service,
            factorDao = database.factorDao(),
            factorValues = factorValues,
            signalRuleDao = database.strategySignalRuleDao(),
            signalRuleEngine = SignalRuleEngine(database.marketDailyBarDao()),
        )
        source = RoomStrategyDataSource(
            strategyService = service,
            previewEvaluation = PreviewStrategyEvaluationUseCase(service, loader),
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = registry,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
        )
        samsung = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자", board = Board.KOSPI),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun browsingAndPreview_leaveEveryTableUnchanged() = runBlocking {
        val (strategyId, versionId) = activeStrategy()
        insertBars(100_000, 101_000)
        insertMomentumValue(day2, "72")
        val before = rowCounts()

        source.strategies()
        source.versions(strategyId)
        source.snapshot(versionId)
        source.calculationVersions(FactorCodes.MOMENTUM_20D)
        source.strategyCodeExists("MOMENTUM_BASIC")
        source.searchInstruments("삼성")
        source.recentTradeDates(samsung, 10)
        val preview = source.preview(versionId, samsung, day2)

        assertEquals(StrategyEvaluationStatus.SUCCESS, preview.status)
        assertEquals(before, rowCounts())
        listOf("stock_evaluations", "stock_evaluation_details", "orders", "executions", "cash_ledger", "positions", "trade_audit_logs")
            .forEach { assertEquals(it, 0, before.getValue(it)) }
    }

    @Test
    fun factorPreview_isAKoreanDecision_withContributions_andPersistsNoEvaluation() = runBlocking {
        val (_, versionId) = activeStrategy()
        insertMomentumValue(day2, "72")

        val result = source.preview(versionId, samsung, day2)
        val view = StrategyPresenter.preview(result, StrategyFixtures.SAMSUNG.copy(instrumentId = samsung), day2, emptyList())

        assertEquals(TradeDecision.BUY, view.decision)
        assertEquals("매수", view.decisionLabel)
        assertEquals("팩터 전략", view.sourceLabel)
        assertEquals("72", view.score)
        assertEquals("기여도 72", view.factorRows.single().contribution)
        assertEquals(0, database.stockEvaluationDao().countEvaluations())
    }

    @Test
    fun signalRulePreview_fromTheRealEngine_hasNoQuantScore_andCreatesNoOrder() = runBlocking {
        val (_, versionId) = activeStrategy(withRules = true)
        insertBars(100_000, 94_000)
        val before = rowCounts()

        val result = source.preview(versionId, samsung, day2)
        val rules = service.findSignalRules(versionId).map(StrategyPresenter::rule)
        val view = StrategyPresenter.preview(result, StrategyFixtures.SAMSUNG.copy(instrumentId = samsung), day2, rules)

        assertEquals(DecisionSource.SIGNAL_RULE, result.decisionSource)
        assertEquals("매수", view.decisionLabel)
        assertEquals("신호 규칙", view.sourceLabel)
        assertNull(view.score)
        assertEquals("일간 등락률 -6% · 'DIP_BUY' 일간 등락률 -5% 이하 → 매수", view.ruleLine)
        assertEquals(before, rowCounts())
    }

    @Test
    fun createStrategy_createsTheStrategyAndItsDraftV1() = runBlocking {
        val (strategyId, versionId) = source.createStrategy("VALUE_BASIC", "가치 전략")

        val version = service.findStrategyVersion(versionId)!!
        assertEquals(strategyId, version.strategyId)
        assertEquals(1, version.versionNo)
        assertEquals(StrategyVersionStatus.DRAFT, version.status)
        assertTrue(source.strategyCodeExists(" VALUE_BASIC "))
    }

    @Test
    fun copyToDraft_usesTheExistingCopy_keepingTheSourceActive() = runBlocking {
        val (_, activeId) = activeStrategy(withRules = true)

        val copyId = source.copyToDraft(activeId)

        val copy = source.snapshot(copyId)!!
        assertEquals(StrategyVersionStatus.DRAFT, copy.version.status)
        assertEquals(2, copy.version.versionNo)
        assertEquals(1, copy.weights.size)
        assertEquals(listOf("DIP_BUY", "SPIKE_SELL"), copy.rules.map { it.ruleCode })
        assertEquals(StrategyVersionStatus.ACTIVE, service.findStrategyVersion(activeId)!!.status)
    }

    @Test
    fun activate_goesThroughTheService_includingItsConflictCheck() = runBlocking {
        val strategyId = service.createStrategy("MOMENTUM_BASIC", "기본 모멘텀 전략")
        val draft = service.createDraftVersion(strategyId)
        source.saveWeights(draft, listOf(momentumWeight("100")))
        source.saveRule(draft, "A", SignalOperator.LTE, "-5", SignalAction.BUY, 10)
        source.saveRule(draft, "B", SignalOperator.GTE, "3", SignalAction.SELL, 10)

        val conflict = source.activate(draft) as StrategyActivationResult.Failed
        assertEquals(StrategyActivationFailure.CONFLICTING_SIGNAL_RULES, conflict.kind)
        assertEquals(StrategyVersionStatus.DRAFT, service.findStrategyVersion(draft)!!.status)

        source.saveRule(draft, "B", SignalOperator.GTE, "3", SignalAction.SELL, 20)
        assertEquals(2, service.findSignalRules(draft).size)
        assertTrue(source.activate(draft) is StrategyActivationResult.Success)
        assertEquals(StrategyVersionStatus.ACTIVE, service.findStrategyVersion(draft)!!.status)
    }

    @Test
    fun activeVersion_rejectsEveryEdit_withTheImmutableKind() = runBlocking {
        val (_, activeId) = activeStrategy(withRules = true)
        val ruleId = service.findSignalRules(activeId).first().id
        val edits: List<suspend () -> Unit> = listOf(
            { source.saveThresholds(activeId, score("30"), score("80")) },
            { source.saveWeights(activeId, listOf(momentumWeight("50"))) },
            { source.saveRule(activeId, "NEW", SignalOperator.GTE, "4", SignalAction.SELL, 30) },
            { source.deleteRule(ruleId) },
        )

        edits.forEach { edit ->
            val error = assertThrows(StrategyVersionException::class.java) { runBlocking { edit() } }
            assertEquals(StrategyErrorKind.IMMUTABLE, error.kind)
            assertEquals("사용 중이거나 종료된 버전은 수정할 수 없습니다.", StrategyPresenter.failure(error))
        }
        assertEquals(score("40"), service.findStrategyVersion(activeId)!!.sellThreshold)
    }

    @Test
    fun saveWeights_resolvesFactorCodes_andKeepsGatesAndVersion() = runBlocking {
        val strategyId = service.createStrategy("MOMENTUM_BASIC", "기본 모멘텀 전략")
        val draft = service.createDraftVersion(strategyId)

        source.saveWeights(
            draft,
            listOf(momentumWeight("60").copy(minScoreStored = score("30"), maxScoreStored = score("90"))),
        )

        val snapshot = source.snapshot(draft)!!
        val stored = snapshot.weights.single()
        assertEquals(FactorCodes.MOMENTUM_20D, snapshot.factorCodes.getValue(stored.factorId))
        assertEquals(600_000L, stored.weight)
        assertEquals(score("30"), stored.minScore)
        assertEquals(score("90"), stored.maxScore)
        assertEquals("v1", stored.factorCalculationVersion)
    }

    @Test
    fun recentTradeDates_areNewestFirst_andSearchIsActiveOnly() = runBlocking {
        insertBars(100_000, 101_000)
        database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "009999", name = "삼성폐지", board = Board.KOSPI, isActive = false),
        )

        assertEquals(listOf(day2, day1), source.recentTradeDates(samsung, 10))
        assertEquals(listOf("005930"), source.searchInstruments("삼성").map { it.symbol })
    }

    private suspend fun activeStrategy(withRules: Boolean = false): Pair<Long, Long> {
        val strategyId = service.createStrategy("MOMENTUM_BASIC", "기본 모멘텀 전략")
        val versionId = service.createDraftVersion(strategyId)
        source.saveWeights(versionId, listOf(momentumWeight("100")))
        if (withRules) {
            source.saveRule(versionId, "DIP_BUY", SignalOperator.LTE, "-5", SignalAction.BUY, 10)
            source.saveRule(versionId, "SPIKE_SELL", SignalOperator.GTE, "3", SignalAction.SELL, 20)
        }
        assertTrue(service.activateStrategyVersion(versionId) is StrategyActivationResult.Success)
        return strategyId to versionId
    }

    private fun momentumWeight(percent: String) = DraftWeight(
        factorCode = FactorCodes.MOMENTUM_20D,
        weightStored = StrategyScoreMath.percentToWeightStored(BigDecimal(percent)),
        enabled = true,
        calculationVersion = "v1",
        minScoreStored = null,
        maxScoreStored = null,
    )

    private suspend fun insertMomentumValue(date: LocalDate, normalized: String) {
        database.factorDao().insertValue(
            FactorValueEntity(
                instrumentId = samsung,
                factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id,
                evaluationDate = date,
                rawValue = "4.2",
                normalizedScore = score(normalized),
                source = "TEST",
                calculationVersion = "v1",
            ),
        )
    }

    private suspend fun insertBars(closeDay1: Long, closeDay2: Long) {
        listOf(day1 to closeDay1, day2 to closeDay2).forEach { (date, close) ->
            database.marketDailyBarDao().insert(
                MarketDailyBarEntity(
                    instrumentId = samsung,
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
    }

    private fun rowCounts(): Map<String, Int> {
        val tables = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { while (it.moveToNext()) tables += it.getString(0) }
        return tables.associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
        }
    }

    private fun score(value: String): Long = StrategyScoreMath.scoreToStored(BigDecimal(value))
}
