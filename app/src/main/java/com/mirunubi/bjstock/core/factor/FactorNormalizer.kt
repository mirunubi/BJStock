package com.mirunubi.bjstock.core.factor

import java.math.BigDecimal
import java.math.RoundingMode

class LinearNormalizer(
    private val valueAtZero: BigDecimal,
    private val valueAtHundred: BigDecimal,
) : FactorNormalizer {
    override fun normalize(rawValue: BigDecimal): BigDecimal {
        val span = valueAtHundred.subtract(valueAtZero)
        require(span.compareTo(FactorMath.ZERO) != 0) { "normalizer span must be non-zero" }
        val score = rawValue.subtract(valueAtZero)
            .multiply(FactorMath.HUNDRED, FactorMath.CONTEXT)
            .divide(span, FactorMath.CONTEXT)
        return FactorMath.clampScore(score)
    }
}

class PiecewiseLinearNormalizer(
    points: List<Pair<BigDecimal, BigDecimal>>,
) : FactorNormalizer {
    private val knots = points.sortedBy { it.first }

    init {
        require(knots.size >= 2) { "piecewise normalizer needs at least two points" }
    }

    override fun normalize(rawValue: BigDecimal): BigDecimal {
        if (rawValue <= knots.first().first) {
            return FactorMath.clampScore(knots.first().second)
        }
        if (rawValue >= knots.last().first) {
            return FactorMath.clampScore(knots.last().second)
        }
        for (index in 0 until knots.lastIndex) {
            val (x0, y0) = knots[index]
            val (x1, y1) = knots[index + 1]
            if (rawValue >= x0 && rawValue <= x1) {
                val span = x1.subtract(x0)
                val score = y0.add(
                    rawValue.subtract(x0)
                        .multiply(y1.subtract(y0), FactorMath.CONTEXT)
                        .divide(span, FactorMath.CONTEXT),
                )
                return FactorMath.clampScore(score)
            }
        }
        return FactorMath.clampScore(knots.last().second)
    }
}

object FactorScoreCodec {
    fun toStored(score: BigDecimal): Long {
        val clamped = FactorMath.clampScore(score)
        return clamped
            .multiply(BigDecimal(com.mirunubi.bjstock.core.database.mapping.NumericMapping.SCORE_FACTOR))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun toDisplay(stored: Long): BigDecimal {
        return BigDecimal(stored)
            .divide(
                BigDecimal(com.mirunubi.bjstock.core.database.mapping.NumericMapping.SCORE_FACTOR),
                4,
                RoundingMode.HALF_UP,
            )
    }
}
