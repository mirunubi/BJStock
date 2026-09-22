package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.TradeDecision

enum class StrategyEvaluationStatus {
    SUCCESS,
    FACTOR_GATE_FAILED,
    INSUFFICIENT_FACTORS,
    INVALID_STRATEGY,
    ALREADY_EVALUATED,
}

data class StrategyFactorDetail(
    val factorId: Long,
    val factorCode: String,
    val calculationVersion: String,
    val rawValue: String?,
    val factorScoreStored: Long,
    val weightStored: Long,
    val weightedScoreStored: Long,
    val minScoreStored: Long?,
    val maxScoreStored: Long?,
    val gateFailed: Boolean,
)

data class StrategyEvaluationResult(
    val status: StrategyEvaluationStatus,
    val quantScoreStored: Long? = null,
    val quantDecision: TradeDecision? = null,
    val factorDetails: List<StrategyFactorDetail> = emptyList(),
    val failedFactorCode: String? = null,
    val missingFactorCodes: List<String> = emptyList(),
    val message: String? = null,
    val persistedEvaluationId: Long? = null,
    val decisionSource: DecisionSource = DecisionSource.FACTOR_STRATEGY,
    val triggeredRuleId: Long? = null,
    val reasonText: String? = null,
    val metricCode: String? = null,
    val observedValue: String? = null,
    val thresholdValue: String? = null,
) {
    val isPersistable: Boolean
        get() = status == StrategyEvaluationStatus.SUCCESS ||
            status == StrategyEvaluationStatus.FACTOR_GATE_FAILED
}
