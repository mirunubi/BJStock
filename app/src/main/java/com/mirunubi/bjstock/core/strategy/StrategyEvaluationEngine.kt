package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.model.TradeDecision

class StrategyEvaluationEngine {
    fun evaluate(
        version: StrategyVersionEntity,
        weights: List<StrategyFactorWeightEntity>,
        factorCodesById: Map<Long, String>,
        valuesByFactorId: Map<Long, FactorValueEntity>,
    ): StrategyEvaluationResult {
        val thresholdError = StrategyVersionRules.thresholdError(
            version.sellThreshold,
            version.buyThreshold,
        )
        val enabled = weights.filter { it.enabled }.sortedBy { it.factorId }
        val weightError = StrategyVersionRules.enabledWeightSumError(enabled)
        if (thresholdError != null || weightError != null || enabled.isEmpty()) {
            return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INVALID_STRATEGY,
                message = thresholdError ?: weightError ?: "enabled factor count must be at least 1",
            )
        }

        val missing = enabled.mapNotNull { weight ->
            val value = valuesByFactorId[weight.factorId]
            val code = factorCodesById[weight.factorId] ?: "factor:${weight.factorId}"
            val exactVersion = value != null &&
                value.calculationVersion == weight.factorCalculationVersion &&
                value.normalizedScore != null
            if (exactVersion) null else code
        }
        if (missing.isNotEmpty()) {
            return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INSUFFICIENT_FACTORS,
                missingFactorCodes = missing,
                message = "INSUFFICIENT_FACTORS: ${missing.joinToString()}",
            )
        }

        val details = enabled.map { weight ->
            val value = valuesByFactorId.getValue(weight.factorId)
            val score = value.normalizedScore ?: error("normalized score required")
            val weighted = StrategyScoreMath.weightedScoreStored(score, weight.weight)
            val gateFailed = StrategyVersionRules.gateFailed(score, weight.minScore, weight.maxScore)
            StrategyFactorDetail(
                factorId = weight.factorId,
                factorCode = factorCodesById.getValue(weight.factorId),
                calculationVersion = weight.factorCalculationVersion,
                rawValue = value.rawValue,
                factorScoreStored = score,
                weightStored = weight.weight,
                weightedScoreStored = weighted,
                minScoreStored = weight.minScore,
                maxScoreStored = weight.maxScore,
                gateFailed = gateFailed,
            )
        }
        val quant = StrategyScoreMath.quantScoreStored(details.map { it.weightedScoreStored })
        val failed = details.firstOrNull { it.gateFailed }
        return if (failed != null) {
            StrategyEvaluationResult(
                status = StrategyEvaluationStatus.FACTOR_GATE_FAILED,
                quantScoreStored = quant,
                quantDecision = TradeDecision.NO_ACTION,
                factorDetails = details,
                failedFactorCode = failed.factorCode,
                message = "Factor Gate Failed: ${failed.factorCode}",
            )
        } else {
            StrategyEvaluationResult(
                status = StrategyEvaluationStatus.SUCCESS,
                quantScoreStored = quant,
                quantDecision = StrategyScoreMath.decide(
                    quantScoreStored = quant,
                    sellThresholdStored = version.sellThreshold,
                    buyThresholdStored = version.buyThreshold,
                ),
                factorDetails = details,
            )
        }
    }
}

object StrategyVersionRules {
    fun thresholdError(sellThresholdStored: Long, buyThresholdStored: Long): String? {
        if (
            sellThresholdStored < 0L ||
            buyThresholdStored < 0L ||
            sellThresholdStored > StrategyScoreMath.MAX_SCORE_STORED ||
            buyThresholdStored > StrategyScoreMath.MAX_SCORE_STORED
        ) {
            return "thresholds must satisfy 0 <= sell < buy <= 100"
        }
        if (sellThresholdStored >= buyThresholdStored) {
            return "sell_threshold must be less than buy_threshold"
        }
        return null
    }

    fun enabledWeightSumError(enabled: List<StrategyFactorWeightEntity>): String? {
        val sum = enabled.sumOf { it.weight }
        return if (sum == NumericMapping.WEIGHT_FACTOR) {
            null
        } else {
            "enabled weight sum must be exactly 1.0 (stored ${NumericMapping.WEIGHT_FACTOR}), was $sum"
        }
    }

    fun gateSettingError(minScoreStored: Long?, maxScoreStored: Long?): String? {
        if (minScoreStored != null && (minScoreStored < 0L || minScoreStored > StrategyScoreMath.MAX_SCORE_STORED)) {
            return "min_score must be 0..100"
        }
        if (maxScoreStored != null && (maxScoreStored < 0L || maxScoreStored > StrategyScoreMath.MAX_SCORE_STORED)) {
            return "max_score must be 0..100"
        }
        if (minScoreStored != null && maxScoreStored != null && minScoreStored > maxScoreStored) {
            return "min_score must be <= max_score"
        }
        return null
    }

    fun weightRangeError(weightStored: Long): String? {
        return if (weightStored in 0L..NumericMapping.WEIGHT_FACTOR) {
            null
        } else {
            "weight must be 0..1"
        }
    }

    fun calculationVersionError(version: String): String? {
        return if (version.trim().isEmpty()) {
            "factor_calculation_version must not be blank"
        } else {
            null
        }
    }

    fun gateFailed(factorScoreStored: Long, minScoreStored: Long?, maxScoreStored: Long?): Boolean {
        if (minScoreStored != null && factorScoreStored < minScoreStored) return true
        if (maxScoreStored != null && factorScoreStored > maxScoreStored) return true
        return false
    }
}
