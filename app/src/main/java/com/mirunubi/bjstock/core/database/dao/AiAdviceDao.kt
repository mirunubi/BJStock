package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.AiAdviceRequestEntity
import com.mirunubi.bjstock.core.database.entity.AiAdviceResultEntity

@Dao
interface AiAdviceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(entity: AiAdviceRequestEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResult(entity: AiAdviceResultEntity): Long

    @Query("SELECT * FROM ai_advice_requests WHERE id = :id LIMIT 1")
    suspend fun findRequestById(id: Long): AiAdviceRequestEntity?

    @Query(
        """
        SELECT * FROM ai_advice_requests
        WHERE evaluation_id = :evaluationId
        ORDER BY id DESC
        """,
    )
    suspend fun findRequestsByEvaluation(evaluationId: Long): List<AiAdviceRequestEntity>

    @Query("SELECT * FROM ai_advice_results WHERE request_id = :requestId LIMIT 1")
    suspend fun findResultByRequest(requestId: Long): AiAdviceResultEntity?

    @Query("SELECT COUNT(*) FROM ai_advice_results WHERE request_id = :requestId")
    suspend fun countResultsByRequest(requestId: Long): Int

    @Query("SELECT COUNT(*) FROM ai_advice_requests WHERE evaluation_id = :evaluationId")
    suspend fun countRequestsByEvaluation(evaluationId: Long): Int
}
