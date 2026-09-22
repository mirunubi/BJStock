package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.model.TradeDecision
import java.math.BigDecimal
import java.math.RoundingMode

object StrategyScoreMath {
    val SCORE_FACTOR = BigDecimal(NumericMapping.SCORE_FACTOR)
    val WEIGHT_FACTOR = BigDecimal(NumericMapping.WEIGHT_FACTOR)
    val RATIO_FACTOR = BigDecimal(NumericMapping.RATIO_FACTOR)
    val ROUNDING = RoundingMode.HALF_UP
    val MAX_SCORE_STORED = NumericMapping.SCORE_FACTOR * 100L

    fun weightedScoreStored(factorScoreStored: Long, weightStored: Long): Long {
        return BigDecimal(factorScoreStored)
            .multiply(BigDecimal(weightStored))
            .multiply(RATIO_FACTOR)
            .divide(SCORE_FACTOR.multiply(WEIGHT_FACTOR), 0, ROUNDING)
            .longValueExact()
    }

    fun quantScoreStored(weightedScoresStored: List<Long>): Long {
        val sum = weightedScoresStored.fold(BigDecimal.ZERO) { acc, value ->
            acc.add(BigDecimal(value))
        }
        return sum.multiply(SCORE_FACTOR)
            .divide(RATIO_FACTOR, 0, ROUNDING)
            .longValueExact()
    }

    fun decide(
        quantScoreStored: Long,
        sellThresholdStored: Long,
        buyThresholdStored: Long,
    ): TradeDecision {
        return when {
            quantScoreStored >= buyThresholdStored -> TradeDecision.BUY
            quantScoreStored <= sellThresholdStored -> TradeDecision.SELL
            else -> TradeDecision.HOLD
        }
    }

    fun scoreToStored(score: BigDecimal): Long =
        score.multiply(SCORE_FACTOR).setScale(0, ROUNDING).longValueExact()

    fun scoreToDisplay(stored: Long): BigDecimal =
        BigDecimal(stored).divide(SCORE_FACTOR, NumericMapping.SCORE_SCALE, ROUNDING)

    fun weightToStored(weight: BigDecimal): Long =
        weight.multiply(WEIGHT_FACTOR).setScale(0, ROUNDING).longValueExact()

    fun weightToDisplay(stored: Long): BigDecimal =
        BigDecimal(stored).divide(WEIGHT_FACTOR, NumericMapping.WEIGHT_SCALE, ROUNDING)

    fun percentToWeightStored(percent: BigDecimal): Long =
        percent.divide(BigDecimal(100), NumericMapping.WEIGHT_SCALE + 2, ROUNDING)
            .multiply(WEIGHT_FACTOR)
            .setScale(0, ROUNDING)
            .longValueExact()

    fun weightStoredToPercent(stored: Long): BigDecimal =
        BigDecimal(stored)
            .multiply(BigDecimal(100))
            .divide(WEIGHT_FACTOR, 2, ROUNDING)
}
