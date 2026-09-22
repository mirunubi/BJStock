package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.StrategySignalRuleDao
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.model.DecisionSource
import java.time.LocalDate

class StrategyEvaluationLoader(
    private val strategyService: StrategyVersionService,
    private val factorDao: FactorDao,
    private val factorValues: FactorValueRepository,
    private val signalRuleDao: StrategySignalRuleDao,
    private val signalRuleEngine: SignalRuleEngine,
    private val engine: StrategyEvaluationEngine = StrategyEvaluationEngine(),
) {
    suspend fun evaluate(
        version: StrategyVersionEntity,
        instrumentId: Long,
        evaluationDate: LocalDate,
    ): StrategyEvaluationResult {
        val rules = signalRuleDao.findEnabledByVersion(version.id)
        val trigger = signalRuleEngine.evaluateDailyChangePct(
            instrumentId = instrumentId,
            asOfDate = evaluationDate,
            rules = rules,
        )
        if (trigger != null) {
            return trigger.toDecisionResult()
        }

        val weights = strategyService.findWeights(version.id)
        val definitions = factorValues.findAllDefinitions().associateBy { it.id }
        val enabled = weights.filter { it.enabled }
        val values = mutableMapOf<Long, FactorValueEntity>()
        enabled.forEach { weight ->
            val value = factorDao.findValue(
                instrumentId = instrumentId,
                factorId = weight.factorId,
                evaluationDate = evaluationDate,
                calculationVersion = weight.factorCalculationVersion,
            )
            if (value != null) {
                values[weight.factorId] = value
            }
        }
        val factorResult = engine.evaluate(
            version = version,
            weights = weights,
            factorCodesById = definitions.mapValues { it.value.factorCode },
            valuesByFactorId = values,
        )
        return enrichFactorReason(factorResult, version)
    }

    private fun enrichFactorReason(
        result: StrategyEvaluationResult,
        version: StrategyVersionEntity,
    ): StrategyEvaluationResult {
        if (result.status != StrategyEvaluationStatus.SUCCESS || result.quantDecision == null) {
            return result.copy(decisionSource = DecisionSource.FACTOR_STRATEGY)
        }
        val score = result.quantScoreStored ?: return result.copy(
            decisionSource = DecisionSource.FACTOR_STRATEGY,
        )
        val scoreDisplay = StrategyScoreMath.scoreToDisplay(score).toPlainString()
        val buy = StrategyScoreMath.scoreToDisplay(version.buyThreshold).toPlainString()
        val sell = StrategyScoreMath.scoreToDisplay(version.sellThreshold).toPlainString()
        val reason = when (result.quantDecision) {
            com.mirunubi.bjstock.core.model.TradeDecision.BUY ->
                "Quant score $scoreDisplay >= BUY threshold $buy"
            com.mirunubi.bjstock.core.model.TradeDecision.SELL ->
                "Quant score $scoreDisplay <= SELL threshold $sell"
            com.mirunubi.bjstock.core.model.TradeDecision.HOLD ->
                "Quant score $scoreDisplay between SELL $sell and BUY $buy"
            else -> result.message
        }
        return result.copy(
            decisionSource = DecisionSource.FACTOR_STRATEGY,
            reasonText = reason,
            message = reason ?: result.message,
        )
    }
}
