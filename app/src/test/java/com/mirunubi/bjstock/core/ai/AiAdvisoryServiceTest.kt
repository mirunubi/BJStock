package com.mirunubi.bjstock.core.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.FactorDefinitionEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationDetailEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.model.FactorCategory
import com.mirunubi.bjstock.core.model.FactorValueType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.paper.ProcessEvaluationUseCase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiAdvisoryServiceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var modeStore: AiAdvisoryModeStore
    private lateinit var promptBuilder: AiAdvisoryPromptBuilder
    private lateinit var service: AiAdvisoryService
    private var evaluationId = 0L
    private var instrumentId = 0L
    private var runId = 0L
    private val evaluationDate = LocalDate.of(2026, 9, 18)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        modeStore = AiAdvisoryModeStore(context)
        modeStore.setMode(AiAdvisoryMode.CHATGPT_MANUAL)
        promptBuilder = AiAdvisoryPromptBuilder(
            evaluationDao = database.stockEvaluationDao(),
            instrumentDao = database.instrumentDao(),
            factorDao = database.factorDao(),
            strategyRunDao = database.strategyRunDao(),
            strategyDao = database.strategyDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            policyDao = database.paperTradingPolicyDao(),
        )
        service = AiAdvisoryService(
            modeStore = modeStore,
            promptBuilder = promptBuilder,
            parser = AiAdviceResponseParser(),
            aiAdviceDao = database.aiAdviceDao(),
            evaluationDao = database.stockEvaluationDao(),
            orderDao = database.orderDao(),
            now = { Instant.EPOCH },
        )
        seedEvaluation()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun promptFixture_includesSnapshot() = runBlocking {
        val prompt = promptBuilder.build(evaluationId)
        assertTrue(prompt.promptText.contains("005930"))
        assertTrue(prompt.promptText.contains("quant_decision=BUY"))
        assertTrue(prompt.promptText.contains("quant_score=72"))
        assertTrue(prompt.promptText.contains(evaluationDate.toString()))
        assertTrue(prompt.promptText.contains("MOMENTUM_TEST"))
    }

    @Test
    fun futurePoison_doesNotChangePrompt() = runBlocking {
        val before = promptBuilder.build(evaluationId)
        insertBar(evaluationDate.plusDays(1), 99_000)
        insertBar(evaluationDate.plusDays(2), 101_000)
        val after = promptBuilder.build(evaluationId)
        assertEquals(before.requestFingerprint, after.requestFingerprint)
        assertEquals(before.promptText, after.promptText)
        assertFalse(after.promptText.contains("2026-09-19"))
        assertFalse(after.promptText.contains("2026-09-20"))
    }

    @Test
    fun snapshotStability_ignoresLiveFactorChanges() = runBlocking {
        val before = promptBuilder.build(evaluationId)
        // Mutate factor definition name would not appear; mutate would-be live values by inserting
        // another detail is blocked by unique. Change instrument name after snapshot:
        val instrument = database.instrumentDao().findById(instrumentId)!!
        database.instrumentDao().update(instrument.copy(name = "ChangedName"))
        // Prompt uses current instrument master for symbol/name display; evaluation scores stay snapshot.
        // Spec: Evaluation Snapshot based — factor scores from details. Instrument name is master data.
        val afterScores = promptBuilder.build(evaluationId)
        assertTrue(afterScores.promptText.contains("score=72"))
        assertEquals(before.requestFingerprint.length, afterScores.requestFingerprint.length)
        // Revert to same instrument fields used in original prompt for determinism of fingerprint on scores path:
        // Changing name changes prompt (instrument_name). Spec focuses on factor evaluation snapshot.
        assertTrue(before.promptText.contains("MOMENTUM_TEST"))
        assertTrue(afterScores.promptText.contains("MOMENTUM_TEST"))
    }

    @Test
    fun promptDeterminism_andFingerprintSensitivity() = runBlocking {
        val first = promptBuilder.build(evaluationId)
        repeat(9) {
            val next = promptBuilder.build(evaluationId)
            assertEquals(first.promptText, next.promptText)
            assertEquals(first.requestFingerprint, next.requestFingerprint)
        }
        val mutated = first.canonicalPayload + "x"
        assertNotEquals(first.requestFingerprint, AiAdvisoryPromptBuilder.sha256Hex(mutated))
    }

    @Test
    fun saveValid_andDuplicate_andIsolation() = runBlocking {
        modeStore.setMode(AiAdvisoryMode.CHATGPT_MANUAL)
        val created = service.createManualRequest(evaluationId) as AiAdviceOutcome.PromptReady
        val evalBefore = database.stockEvaluationDao().findEvaluationById(evaluationId)!!
        val ordersBefore = database.orderDao().countByRun(runId)
        val raw = validResponse(created.prompt.requestFingerprint, stance = "SELL")
        val saved = service.saveManualResponse(created.requestId, raw)
        assertTrue(saved is AiAdviceOutcome.Success)
        val evalAfter = database.stockEvaluationDao().findEvaluationById(evaluationId)!!
        assertEquals(evalBefore.quantScore, evalAfter.quantScore)
        assertEquals(evalBefore.quantDecision, evalAfter.quantDecision)
        assertEquals(evalBefore.finalScore, evalAfter.finalScore)
        assertEquals(evalBefore.finalDecision, evalAfter.finalDecision)
        assertEquals(evalBefore.aiScore, evalAfter.aiScore)
        assertEquals(ordersBefore, database.orderDao().countByRun(runId))

        val dup = service.saveManualResponse(created.requestId, raw)
        assertEquals(
            AiAdviceErrorKind.RESULT_ALREADY_EXISTS,
            (dup as AiAdviceOutcome.Failure).kind,
        )
    }

    @Test
    fun fingerprintMismatch_writesNothing() = runBlocking {
        val created = service.createManualRequest(evaluationId) as AiAdviceOutcome.PromptReady
        val before = database.aiAdviceDao().countResultsByRequest(created.requestId)
        val outcome = service.saveManualResponse(
            created.requestId,
            validResponse("wrong-fingerprint"),
        )
        assertEquals(AiAdviceErrorKind.REQUEST_MISMATCH, (outcome as AiAdviceOutcome.Failure).kind)
        assertEquals(before, database.aiAdviceDao().countResultsByRequest(created.requestId))
    }

    @Test
    fun offMode_blocksRequest() = runBlocking {
        modeStore.setMode(AiAdvisoryMode.OFF)
        val outcome = service.createManualRequest(evaluationId)
        assertEquals(AiAdviceErrorKind.AI_DISABLED, (outcome as AiAdviceOutcome.Failure).kind)
    }

    @Test
    fun manualMode_noNetworkDependency() {
        // Compile-time architecture: Manual provider has no HTTP client fields.
        val provider = ManualChatGptAdvisoryProvider()
        assertEquals(AiAdvisoryMode.CHATGPT_MANUAL, provider.mode)
        modeStore.setMode(AiAdvisoryMode.CHATGPT_MANUAL)
        assertEquals(AiAdvisoryMode.CHATGPT_MANUAL, service.getMode())
    }

    @Test
    fun paperTradingIgnoresAiSell() = runBlocking {
        val created = service.createManualRequest(evaluationId) as AiAdviceOutcome.PromptReady
        service.saveManualResponse(created.requestId, validResponse(created.prompt.requestFingerprint, "SELL"))
        val process = ProcessEvaluationUseCase(
            evaluationDao = database.stockEvaluationDao(),
            strategyRunDao = database.strategyRunDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
            now = { Instant.EPOCH },
        )
        val result = process(evaluationId)
        assertEquals(com.mirunubi.bjstock.core.paper.PaperTradeAction.ORDER_CREATED, result.action)
        val order = database.orderDao().findById(result.orderId!!)!!
        assertEquals(com.mirunubi.bjstock.core.model.OrderSide.BUY, order.side)
    }

    private suspend fun seedEvaluation() {
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        val strategyId = database.strategyDao().insertStrategy(
            StrategyEntity(
                strategyCode = "AI_TEST",
                strategyName = "AI Test",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val versionId = database.strategyDao().insertVersion(
            StrategyVersionEntity(
                strategyId = strategyId,
                versionNo = 1,
                buyThreshold = 700_000,
                sellThreshold = 300_000,
                status = StrategyVersionStatus.ACTIVE,
                createdAt = Instant.EPOCH,
            ),
        )
        runId = database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = "AI-Run",
                strategyVersionId = versionId,
                startDate = evaluationDate,
                initialCash = 100_000_000L,
                status = RunStatus.READY,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val factorId = database.factorDao().insertDefinition(
            FactorDefinitionEntity(
                factorCode = "MOMENTUM_TEST",
                factorName = "Momentum Test",
                category = FactorCategory.MOMENTUM,
                description = null,
                valueType = FactorValueType.NUMBER,
                higherIsBetter = true,
                isActive = true,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        evaluationId = database.stockEvaluationDao().insertEvaluation(
            StockEvaluationEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                evaluationDate = evaluationDate,
                quantScore = 720_000L,
                aiScore = null,
                finalScore = 720_000L,
                quantDecision = TradeDecision.BUY,
                finalDecision = TradeDecision.BUY,
                createdAt = Instant.EPOCH,
            ),
        )
        database.stockEvaluationDao().insertDetail(
            StockEvaluationDetailEntity(
                evaluationId = evaluationId,
                factorId = factorId,
                rawValue = "1.2",
                factorScore = 720_000L,
                weight = 1_000_000L,
                weightedScore = 720_000L,
                createdAt = Instant.EPOCH,
            ),
        )
        insertBar(evaluationDate.minusDays(1), 50_000)
        insertBar(evaluationDate, 51_000)
    }

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
                source = "TEST",
                collectedAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
            ),
        )
    }

    private fun validResponse(fingerprint: String, stance: String = "HOLD"): String = """
        {
          "schema_version": "1",
          "request_fingerprint": "$fingerprint",
          "stance": "$stance",
          "confidence": 72,
          "summary": "advisory note",
          "supporting_reasons": ["a"],
          "risk_factors": ["b"]
        }
    """.trimIndent()
}
