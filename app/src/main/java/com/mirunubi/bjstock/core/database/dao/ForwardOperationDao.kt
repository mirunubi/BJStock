package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import java.time.Instant

@Dao
interface ForwardOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ForwardOperationEntity): Long

    @Query("SELECT * FROM forward_operations WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ForwardOperationEntity?

    @Query("SELECT * FROM forward_operations WHERE operation_key = :operationKey LIMIT 1")
    suspend fun findByKey(operationKey: String): ForwardOperationEntity?

    /** Transitions RUNNING -> terminal exactly once. Returns 0 if the row is not RUNNING. */
    @Query(
        """
        UPDATE forward_operations SET
            status = :status,
            finished_at = :finishedAt,
            final_code = :finalCode,
            safe_message = :safeMessage,
            runs_considered = :runsConsidered,
            runs_processed = :runsProcessed,
            runs_skipped = :runsSkipped,
            cycles_completed = :cyclesCompleted,
            cycles_failed = :cyclesFailed,
            elapsed_ms = :elapsedMs
        WHERE id = :id AND status = 'RUNNING'
        """,
    )
    suspend fun finishRunning(
        id: Long,
        status: ForwardOperationStatus,
        finishedAt: Instant,
        finalCode: String?,
        safeMessage: String?,
        runsConsidered: Int,
        runsProcessed: Int,
        runsSkipped: Int,
        cyclesCompleted: Int,
        cyclesFailed: Int,
        elapsedMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM forward_operations
        WHERE status = 'RUNNING' AND started_at < :processStartCutoff
        ORDER BY started_at ASC, id ASC
        """,
    )
    suspend fun findRunningStartedBefore(processStartCutoff: Instant): List<ForwardOperationEntity>

    /**
     * RUNNING -> FAILED for an operation abandoned by an earlier process. Counts are left as stored and
     * elapsed_ms stays NULL: the real execution duration is unknowable. Returns 0 if the row is not RUNNING.
     */
    @Query(
        """
        UPDATE forward_operations SET
            status = 'FAILED',
            finished_at = :finishedAt,
            final_code = :finalCode,
            safe_message = :safeMessage,
            elapsed_ms = NULL
        WHERE id = :id AND status = 'RUNNING'
        """,
    )
    suspend fun markInterrupted(
        id: Long,
        finishedAt: Instant,
        finalCode: String,
        safeMessage: String,
    ): Int

    @Query("SELECT * FROM forward_operations ORDER BY started_at DESC, id DESC LIMIT :limit")
    suspend fun findRecent(limit: Int = 20): List<ForwardOperationEntity>

    @Query("SELECT COUNT(*) FROM forward_operations")
    suspend fun countAll(): Int
}
