package com.mirunubi.bjstock.core.strategy

import java.time.LocalDate

class PreviewStrategyEvaluationUseCase(
    private val strategyService: StrategyVersionService,
    private val loader: StrategyEvaluationLoader,
) {
    suspend operator fun invoke(
        strategyVersionId: Long,
        instrumentId: Long,
        evaluationDate: LocalDate,
    ): StrategyEvaluationResult {
        val version = strategyService.findStrategyVersion(strategyVersionId)
            ?: return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INVALID_STRATEGY,
                message = "strategy version $strategyVersionId not found",
            )
        return loader.evaluate(version, instrumentId, evaluationDate)
    }
}
