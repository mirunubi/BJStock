package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import java.time.Instant

@Dao
interface ApiErrorLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ApiErrorLogEntity): Long

    @Query(
        """
        SELECT * FROM api_error_logs
        WHERE occurred_at >= :since
        ORDER BY occurred_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun findSince(since: Instant, limit: Int = 100): List<ApiErrorLogEntity>

    @Query("DELETE FROM api_error_logs WHERE occurred_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Instant): Int

    @Query("SELECT COUNT(*) FROM api_error_logs")
    suspend fun countAll(): Int
}
