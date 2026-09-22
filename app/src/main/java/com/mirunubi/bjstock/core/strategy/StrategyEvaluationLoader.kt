package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import java.time.LocalDate

class StrategyEvaluationLoader(
    private val strategyService: StrategyVersionService,
    private val factorDao: FactorDao,
    private val factorValues: FactorValueRepository,
    private val engine: StrategyEvaluationEngine = StrategyEvaluationEngine(),
) {
    suspend fun evaluate(
        version: StrategyVersionEntity,
        instrumentId: Long,
        evaluationDate: LocalDate,
    ): StrategyEvaluationResult {
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
        return engine.evaluate(
            version = version,
            weights = weights,
            factorCodesById = definitions.mapValues { it.value.factorCode },
            valuesByFactorId = values,
        )
    }
}
