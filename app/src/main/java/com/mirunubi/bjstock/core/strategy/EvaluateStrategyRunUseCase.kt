package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import java.time.LocalDate

class EvaluateStrategyRunUseCase(
    private val strategyDao: StrategyDao,
    private val strategyRunDao: StrategyRunDao,
    private val evaluations: StrategyEvaluationRepository,
    private val loader: StrategyEvaluationLoader,
) {
    suspend operator fun invoke(
        strategyRunId: Long,
        instrumentId: Long,
        evaluationDate: LocalDate,
    ): StrategyEvaluationResult {
        val run = strategyRunDao.findById(strategyRunId)
            ?: return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INVALID_STRATEGY,
                message = "strategy run $strategyRunId not found",
            )
        if (run.status !in ALLOWED_RUN_STATUS) {
            return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INVALID_STRATEGY,
                message = "persisted evaluation is allowed only for READY or RUNNING runs",
            )
        }
        val version = strategyDao.findVersionById(run.strategyVersionId)
            ?: return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INVALID_STRATEGY,
                message = "strategy version ${run.strategyVersionId} not found",
            )
        if (version.status != StrategyVersionStatus.ACTIVE) {
            return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INVALID_STRATEGY,
                message = "run evaluation requires an ACTIVE strategy version",
            )
        }
        val existing = evaluations.findEvaluation(strategyRunId, instrumentId, evaluationDate)
        if (existing != null) {
            return StrategyEvaluationResult(
                status = StrategyEvaluationStatus.ALREADY_EVALUATED,
                quantScoreStored = existing.quantScore,
                quantDecision = existing.quantDecision,
                persistedEvaluationId = existing.id,
                message = "evaluation snapshot already exists",
            )
        }
        val computed = loader.evaluate(version, instrumentId, evaluationDate)
        if (!computed.isPersistable) {
            return computed
        }
        val evaluationId = evaluations.persistSnapshot(
            strategyRunId = strategyRunId,
            instrumentId = instrumentId,
            evaluationDate = evaluationDate,
            result = computed,
        )
        return computed.copy(persistedEvaluationId = evaluationId)
    }

    companion object {
        val ALLOWED_RUN_STATUS = setOf(RunStatus.READY, RunStatus.RUNNING)
    }
}
