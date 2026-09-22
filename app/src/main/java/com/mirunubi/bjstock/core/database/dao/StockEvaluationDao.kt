package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.StockEvaluationDetailEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import java.time.LocalDate

@Dao
interface StockEvaluationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvaluation(entity: StockEvaluationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDetail(entity: StockEvaluationDetailEntity): Long

    @Query(
        """
        SELECT * FROM stock_evaluations
        WHERE strategy_run_id = :strategyRunId
          AND instrument_id = :instrumentId
          AND evaluation_date = :evaluationDate
        LIMIT 1
        """,
    )
    suspend fun findEvaluation(
        strategyRunId: Long,
        instrumentId: Long,
        evaluationDate: LocalDate,
    ): StockEvaluationEntity?

    @Query("SELECT * FROM stock_evaluations WHERE id = :id LIMIT 1")
    suspend fun findEvaluationById(id: Long): StockEvaluationEntity?

    @Query(
        """
        SELECT * FROM stock_evaluation_details
        WHERE evaluation_id = :evaluationId
        ORDER BY factor_id
        """,
    )
    suspend fun findDetails(evaluationId: Long): List<StockEvaluationDetailEntity>

    @Query("SELECT COUNT(*) FROM stock_evaluations")
    suspend fun countEvaluations(): Int

    @Query("SELECT COUNT(*) FROM stock_evaluation_details")
    suspend fun countDetails(): Int

    @Query(
        """
        SELECT * FROM stock_evaluations
        WHERE strategy_run_id = :strategyRunId
        ORDER BY evaluation_date ASC, id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<StockEvaluationEntity>
}
