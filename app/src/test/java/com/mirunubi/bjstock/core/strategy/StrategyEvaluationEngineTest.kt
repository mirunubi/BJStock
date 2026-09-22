package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEvaluationEngineTest {
    private val engine = StrategyEvaluationEngine()
    private val date = LocalDate.of(2026, 9, 18)

    @Test
    fun weightedScore_80At25And60At75_isExactly65() {
        val result = engine.evaluate(
            version(),
            listOf(weight(1, 250_000), weight(2, 750_000)),
            mapOf(1L to "A", 2L to "B"),
            mapOf(1L to value(1, "80"), 2L to value(2, "60")),
        )

        assertEquals(StrategyEvaluationStatus.SUCCESS, result.status)
        assertEquals(score("65"), result.quantScoreStored)
        assertEquals(TradeDecision.HOLD, result.quantDecision)
    }

    @Test
    fun thresholdBoundaries_areInclusive() {
        val sell = score("40")
        val buy = score("70")
        assertEquals(TradeDecision.BUY, StrategyScoreMath.decide(score("80"), sell, buy))
        assertEquals(TradeDecision.BUY, StrategyScoreMath.decide(score("70"), sell, buy))
        assertEquals(TradeDecision.HOLD, StrategyScoreMath.decide(score("69.99"), sell, buy))
        assertEquals(TradeDecision.HOLD, StrategyScoreMath.decide(score("41"), sell, buy))
        assertEquals(TradeDecision.SELL, StrategyScoreMath.decide(score("40"), sell, buy))
        assertEquals(TradeDecision.SELL, StrategyScoreMath.decide(score("20"), sell, buy))
    }

    @Test
    fun missingEnabledFactor_isInsufficientAndDoesNotRenormalize() {
        val result = engine.evaluate(
            version(),
            listOf(weight(1, 250_000), weight(2, 750_000)),
            mapOf(1L to "A", 2L to "B"),
            mapOf(1L to value(1, "80")),
        )

        assertEquals(StrategyEvaluationStatus.INSUFFICIENT_FACTORS, result.status)
        assertNull(result.quantScoreStored)
        assertEquals(listOf("B"), result.missingFactorCodes)
    }

    @Test
    fun missingDisabledFactor_isIgnored() {
        val result = engine.evaluate(
            version(),
            listOf(weight(1, 1_000_000), weight(2, 900_000, enabled = false)),
            mapOf(1L to "A", 2L to "B"),
            mapOf(1L to value(1, "80")),
        )

        assertEquals(StrategyEvaluationStatus.SUCCESS, result.status)
        assertEquals(score("80"), result.quantScoreStored)
    }

    @Test
    fun wrongCalculationVersion_isInsufficient() {
        val result = engine.evaluate(
            version(),
            listOf(weight(1, 1_000_000, calculationVersion = "v1")),
            mapOf(1L to "MOMENTUM_20D"),
            mapOf(1L to value(1, "80", calculationVersion = "v2")),
        )

        assertEquals(StrategyEvaluationStatus.INSUFFICIENT_FACTORS, result.status)
        assertEquals(listOf("MOMENTUM_20D"), result.missingFactorCodes)
    }

    @Test
    fun enabledWeightSum_requiresExactScaledOne() {
        assertNotNull(StrategyVersionRules.enabledWeightSumError(listOf(weight(1, 900_000))))
        assertNull(StrategyVersionRules.enabledWeightSumError(listOf(weight(1, 1_000_000))))
        assertNotNull(StrategyVersionRules.enabledWeightSumError(listOf(weight(1, 1_100_000))))
    }

    @Test
    fun thresholdValidation_rejectsInvalidRanges() {
        assertNotNull(StrategyVersionRules.thresholdError(score("70"), score("70")))
        assertNotNull(StrategyVersionRules.thresholdError(score("80"), score("70")))
        assertNotNull(StrategyVersionRules.thresholdError(-1, score("70")))
        assertNotNull(StrategyVersionRules.thresholdError(score("40"), score("100.01")))
    }

    @Test
    fun gateBoundaries_areInclusiveAndOutsideFails() {
        assertTrue(StrategyVersionRules.gateFailed(score("39.99"), score("40"), null))
        assertFalse(StrategyVersionRules.gateFailed(score("40"), score("40"), null))
        assertTrue(StrategyVersionRules.gateFailed(score("80.01"), null, score("80")))
        assertFalse(StrategyVersionRules.gateFailed(score("80"), null, score("80")))
    }

    @Test
    fun gateFailure_keepsQuantScoreButReturnsNoAction() {
        val result = engine.evaluate(
            version(),
            listOf(weight(1, 1_000_000, minScore = score("40"))),
            mapOf(1L to "A"),
            mapOf(1L to value(1, "39.99")),
        )

        assertEquals(StrategyEvaluationStatus.FACTOR_GATE_FAILED, result.status)
        assertEquals(score("39.99"), result.quantScoreStored)
        assertEquals(TradeDecision.NO_ACTION, result.quantDecision)
        assertEquals("A", result.failedFactorCode)
    }

    private fun version() = StrategyVersionEntity(
        id = 7,
        strategyId = 3,
        versionNo = 1,
        buyThreshold = score("70"),
        sellThreshold = score("40"),
        status = StrategyVersionStatus.DRAFT,
    )

    private fun weight(
        factorId: Long,
        weight: Long,
        enabled: Boolean = true,
        calculationVersion: String = "v1",
        minScore: Long? = null,
        maxScore: Long? = null,
    ) = StrategyFactorWeightEntity(
        id = factorId,
        strategyVersionId = 7,
        factorId = factorId,
        weight = weight,
        minScore = minScore,
        maxScore = maxScore,
        enabled = enabled,
        factorCalculationVersion = calculationVersion,
    )

    private fun value(
        factorId: Long,
        score: String,
        calculationVersion: String = "v1",
    ) = FactorValueEntity(
        id = factorId,
        instrumentId = 11,
        factorId = factorId,
        evaluationDate = date,
        rawValue = score,
        normalizedScore = score(score),
        source = "TEST",
        calculationVersion = calculationVersion,
    )

    private fun score(value: String): Long =
        StrategyScoreMath.scoreToStored(BigDecimal(value))
}
