package com.mirunubi.bjstock.core.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
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
class StrategyServicesTest {
    private lateinit var database: BJStockDatabase
    private lateinit var factorValues: FactorValueRepository
    private lateinit var strategyService: StrategyVersionService
    private lateinit var loader: StrategyEvaluationLoader
    private lateinit var evaluationRepository: StrategyEvaluationRepository
    private lateinit var evaluateRun: EvaluateStrategyRunUseCase
    private var instrumentId = 0L
    private var factorId = 0L
    private val date = LocalDate.of(2026, 9, 18)

    @Before
    fun setUp() = runBlocking {
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
            now = { Instant.EPOCH },
        )
        loader = StrategyEvaluationLoader(
            strategyService = strategyService,
            factorDao = database.factorDao(),
            factorValues = factorValues,
        )
        evaluationRepository = StrategyEvaluationRepository(
            database = database,
            evaluationDao = database.stockEvaluationDao(),
            now = { Instant.EPOCH },
        )
        evaluateRun = EvaluateStrategyRunUseCase(
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            evaluations = evaluationRepository,
            loader = loader,
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activationRejectsBadWeightAndUnknownVersionThenAcceptsExactOne() = runBlocking {
        val versionId = createDraft()
        strategyService.upsertDraftWeight(
            strategyVersionId = versionId,
            factorId = factorId,
            weightStored = 900_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        var result = strategyService.activateStrategyVersion(versionId)
        assertTrue(result is StrategyActivationResult.Failed)
        assertEquals(
            StrategyActivationFailure.INVALID_WEIGHT_SUM,
            (result as StrategyActivationResult.Failed).kind,
        )

        strategyService.upsertDraftWeight(
            strategyVersionId = versionId,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = "v99",
        )
        result = strategyService.activateStrategyVersion(versionId)
        assertEquals(
            StrategyActivationFailure.UNSUPPORTED_FACTOR_VERSION,
            (result as StrategyActivationResult.Failed).kind,
        )

        strategyService.upsertDraftWeight(
            strategyVersionId = versionId,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = FactorCalculationVersions.V1,
        )
        result = strategyService.activateStrategyVersion(versionId)
        assertTrue(result is StrategyActivationResult.Success)
        assertEquals(
            StrategyVersionStatus.ACTIVE,
            strategyService.findStrategyVersion(versionId)!!.status,
        )
    }

    @Test
    fun activeThresholdWeightFactorVersionAndGateAreImmutable() = runBlocking {
        val versionId = createActive()

        assertThrows(StrategyVersionException::class.java) {
            runBlocking {
                strategyService.updateDraftThresholds(versionId, score("30"), score("80"))
            }
        }
        assertThrows(StrategyVersionException::class.java) {
            runBlocking {
                strategyService.upsertDraftWeight(
                    versionId,
                    factorId,
                    500_000,
                    true,
                    "v2",
                    minScoreStored = score("20"),
                )
            }
        }
        Unit
    }

    @Test
    fun copyActiveV1CreatesDraftV2WithPinnedConfiguration() = runBlocking {
        val v1 = createActive(minScore = score("30"))
        val v2 = strategyService.copyDraftFrom(v1)

        val first = strategyService.findStrategyVersion(v1)!!
        val second = strategyService.findStrategyVersion(v2)!!
        assertEquals(StrategyVersionStatus.ACTIVE, first.status)
        assertEquals(StrategyVersionStatus.DRAFT, second.status)
        assertEquals(1, first.versionNo)
        assertEquals(2, second.versionNo)
        assertEquals(first.buyThreshold, second.buyThreshold)
        assertEquals(first.sellThreshold, second.sellThreshold)
        val copied = strategyService.findWeights(v2).single()
        assertEquals(1_000_000, copied.weight)
        assertEquals("v1", copied.factorCalculationVersion)
        assertEquals(score("30"), copied.minScore)
    }

    @Test
    fun missingFactorDoesNotPersistEvaluation() = runBlocking {
        val versionId = createActive()
        val runId = createRun(versionId)

        val result = evaluateRun(runId, instrumentId, date)

        assertEquals(StrategyEvaluationStatus.INSUFFICIENT_FACTORS, result.status)
        assertEquals(0, evaluationRepository.countEvaluations())
        assertEquals(0, evaluationRepository.countDetails())
    }

    @Test
    fun previewNeverPersistsAndDraftRunStatusCannotPersist() = runBlocking {
        val draftVersion = createDraft()
        strategyService.upsertDraftWeight(
            strategyVersionId = draftVersion,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = "v1",
        )
        insertValue(date, "75")
        val preview = PreviewStrategyEvaluationUseCase(strategyService, loader)(
            draftVersion,
            instrumentId,
            date,
        )
        assertEquals(StrategyEvaluationStatus.SUCCESS, preview.status)
        assertEquals(0, evaluationRepository.countEvaluations())

        val draftRunId = database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = "Draft",
                strategyVersionId = draftVersion,
                runType = RunType.PAPER,
                startDate = date,
                initialCash = 1_000_000,
                status = RunStatus.DRAFT,
            ),
        )
        val persisted = evaluateRun(draftRunId, instrumentId, date)
        assertEquals(StrategyEvaluationStatus.INVALID_STRATEGY, persisted.status)
        assertEquals(0, evaluationRepository.countEvaluations())
    }

    @Test
    fun retiredVersionCannotCreateNewRun() = runBlocking {
        val versionId = createActive()
        strategyService.retireVersion(versionId)

        assertThrows(StrategyVersionException::class.java) {
            runBlocking { createRun(versionId) }
        }
        Unit
    }

    @Test
    fun snapshotIsImmutableDuplicateIsRejectedAndAiIsIsolated() = runBlocking {
        val versionId = createActive()
        insertValue(date, "71", raw = "1.25")
        val runId = createRun(versionId)

        val first = evaluateRun(runId, instrumentId, date)
        assertEquals(StrategyEvaluationStatus.SUCCESS, first.status)
        val evaluationId = first.persistedEvaluationId!!
        val storedBefore = evaluationRepository.findDetails(evaluationId).single()

        val original = database.factorDao().findValue(instrumentId, factorId, date, "v1")!!
        database.factorDao().updateValue(
            original.copy(rawValue = "999", normalizedScore = score("1")),
        )
        val storedAfter = evaluationRepository.findDetails(evaluationId).single()
        assertEquals(storedBefore.rawValue, storedAfter.rawValue)
        assertEquals(storedBefore.factorScore, storedAfter.factorScore)
        assertEquals(storedBefore.weight, storedAfter.weight)
        assertEquals(storedBefore.weightedScore, storedAfter.weightedScore)

        val duplicate = evaluateRun(runId, instrumentId, date)
        assertEquals(StrategyEvaluationStatus.ALREADY_EVALUATED, duplicate.status)
        assertEquals(1, evaluationRepository.countEvaluations())
        assertEquals(1, evaluationRepository.countDetails())

        val evaluation = database.stockEvaluationDao().findEvaluationById(evaluationId)!!
        assertNull(evaluation.aiScore)
        assertEquals(evaluation.quantScore, evaluation.finalScore)
        assertEquals(evaluation.quantDecision, evaluation.finalDecision)
    }

    @Test
    fun transactionRollsBackHeaderWhenDetailWriteFails() = runBlocking {
        val versionId = createActive()
        insertValue(date, "65")
        val runId = createRun(versionId)
        val computed = loader.evaluate(
            strategyService.findStrategyVersion(versionId)!!,
            instrumentId,
            date,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                evaluationRepository.persistSnapshot(
                    runId,
                    instrumentId,
                    date,
                    computed,
                    failAfterHeader = true,
                )
            }
        }
        assertEquals(0, evaluationRepository.countEvaluations())
        assertEquals(0, evaluationRepository.countDetails())
    }

    @Test
    fun exactDateLookupIgnoresFutureValues() = runBlocking {
        val versionId = createActive()
        insertValue(date, "65")
        val version = strategyService.findStrategyVersion(versionId)!!
        val before = loader.evaluate(version, instrumentId, date)
        insertValue(date.plusDays(1), "100")
        val after = loader.evaluate(version, instrumentId, date)

        assertEquals(before.quantScoreStored, after.quantScoreStored)
        assertEquals(before.quantDecision, after.quantDecision)
    }

    private suspend fun createDraft(): Long {
        val strategyId = strategyService.createStrategy("VALUE_MOMENTUM", "Value Momentum")
        return strategyService.createDraftVersion(strategyId)
    }

    private suspend fun createActive(minScore: Long? = null): Long {
        val versionId = createDraft()
        strategyService.upsertDraftWeight(
            strategyVersionId = versionId,
            factorId = factorId,
            weightStored = 1_000_000,
            enabled = true,
            factorCalculationVersion = "v1",
            minScoreStored = minScore,
        )
        assertTrue(
            strategyService.activateStrategyVersion(versionId) is StrategyActivationResult.Success,
        )
        return versionId
    }

    private suspend fun createRun(versionId: Long): Long {
        val cash = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        val policies = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH }
        return StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = cash,
            policyService = policies,
            factorRegistry = SystemFactorRegistryFactory.create(),
            now = { Instant.EPOCH },
        ).createReadyRun(versionId, "Run", date, 10_000_000)
    }

    private suspend fun insertValue(
        evaluationDate: LocalDate,
        normalizedScore: String,
        raw: String = normalizedScore,
    ) {
        database.factorDao().insertValue(
            FactorValueEntity(
                instrumentId = instrumentId,
                factorId = factorId,
                evaluationDate = evaluationDate,
                rawValue = raw,
                normalizedScore = score(normalizedScore),
                source = "TEST",
                calculationVersion = "v1",
            ),
        )
    }

    private fun score(value: String): Long =
        StrategyScoreMath.scoreToStored(BigDecimal(value))
}
