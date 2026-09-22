package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity

@Dao
interface ExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ExecutionEntity): Long

    @Query("SELECT * FROM executions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ExecutionEntity?

    @Query("SELECT * FROM executions WHERE order_id = :orderId LIMIT 1")
    suspend fun findByOrderId(orderId: Long): ExecutionEntity?

    @Query(
        """
        SELECT * FROM executions
        WHERE order_id IN (
            SELECT id FROM orders WHERE strategy_run_id = :strategyRunId
        )
        ORDER BY id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<ExecutionEntity>

    @Query("SELECT COUNT(*) FROM executions WHERE order_id = :orderId")
    suspend fun countByOrderId(orderId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM executions
        WHERE order_id IN (
            SELECT id FROM orders WHERE strategy_run_id = :strategyRunId
        )
        """,
    )
    suspend fun countByRun(strategyRunId: Long): Int
}
