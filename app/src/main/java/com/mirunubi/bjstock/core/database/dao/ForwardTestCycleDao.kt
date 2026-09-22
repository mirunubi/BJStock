package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import java.time.LocalDate

@Dao
interface ForwardTestCycleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ForwardTestCycleEntity): Long

    @Update
    suspend fun update(entity: ForwardTestCycleEntity)

    @Query(
        """
        SELECT * FROM forward_test_cycles
        WHERE strategy_run_id = :strategyRunId AND market_date = :marketDate
        LIMIT 1
        """,
    )
    suspend fun find(strategyRunId: Long, marketDate: LocalDate): ForwardTestCycleEntity?

    @Query(
        """
        SELECT * FROM forward_test_cycles
        WHERE strategy_run_id = :strategyRunId
        ORDER BY market_date DESC
        LIMIT :limit
        """,
    )
    suspend fun findRecentByRun(strategyRunId: Long, limit: Int = 30): List<ForwardTestCycleEntity>

    @Query(
        """
        SELECT * FROM forward_test_cycles
        WHERE strategy_run_id = :strategyRunId AND status = :status
        ORDER BY market_date ASC
        LIMIT 1
        """,
    )
    suspend fun findOldestByStatus(
        strategyRunId: Long,
        status: ForwardCycleStatus,
    ): ForwardTestCycleEntity?

    @Query(
        """
        SELECT MAX(market_date) FROM forward_test_cycles
        WHERE strategy_run_id = :strategyRunId AND status = 'COMPLETE'
        """,
    )
    suspend fun findLastCompleteDate(strategyRunId: Long): LocalDate?

    @Query(
        """
        SELECT COUNT(*) FROM forward_test_cycles
        WHERE strategy_run_id = :strategyRunId
        """,
    )
    suspend fun countByRun(strategyRunId: Long): Int
}
