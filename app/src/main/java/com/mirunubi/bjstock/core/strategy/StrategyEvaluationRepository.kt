package com.mirunubi.bjstock.core.strategy

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.entity.StockEvaluationDetailEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import java.time.Instant
import java.time.LocalDate

class StrategyEvaluationRepository(
    private val database: BJStockDatabase,
    private val evaluationDao: StockEvaluationDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun findEvaluation(
        strategyRunId: Long,
        instrumentId: Long,
        evaluationDate: LocalDate,
    ) = evaluationDao.findEvaluation(strategyRunId, instrumentId, evaluationDate)

    suspend fun findDetails(evaluationId: Long) = evaluationDao.findDetails(evaluationId)

    suspend fun countEvaluations(): Int = evaluationDao.countEvaluations()

    suspend fun countDetails(): Int = evaluationDao.countDetails()

    suspend fun persistSnapshot(
        strategyRunId: Long,
        instrumentId: Long,
        evaluationDate: LocalDate,
        result: StrategyEvaluationResult,
        failAfterHeader: Boolean = false,
    ): Long {
        val quant = result.quantScoreStored ?: error("persistable evaluation requires quant_score")
        val decision = result.quantDecision ?: error("persistable evaluation requires quant_decision")
        return database.withTransaction {
            val evaluationId = evaluationDao.insertEvaluation(
                StockEvaluationEntity(
                    strategyRunId = strategyRunId,
                    instrumentId = instrumentId,
                    evaluationDate = evaluationDate,
                    quantScore = quant,
                    aiScore = null,
                    finalScore = quant,
                    quantDecision = decision,
                    finalDecision = decision,
                    createdAt = now(),
                ),
            )
            if (failAfterHeader) {
                error("forced evaluation detail failure")
            }
            result.factorDetails.forEach { detail ->
                evaluationDao.insertDetail(
                    StockEvaluationDetailEntity(
                        evaluationId = evaluationId,
                        factorId = detail.factorId,
                        rawValue = detail.rawValue,
                        factorScore = detail.factorScoreStored,
                        weight = detail.weightStored,
                        weightedScore = detail.weightedScoreStored,
                        createdAt = now(),
                    ),
                )
            }
            evaluationId
        }
    }
}
