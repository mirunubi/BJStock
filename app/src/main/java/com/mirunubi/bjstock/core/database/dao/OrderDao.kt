package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OrderEntity): Long

    @Update
    suspend fun update(entity: OrderEntity)

    @Query("SELECT * FROM orders WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): OrderEntity?

    @Query("SELECT * FROM orders WHERE client_order_id = :clientOrderId LIMIT 1")
    suspend fun findByClientOrderId(clientOrderId: String): OrderEntity?

    @Query(
        """
        SELECT * FROM orders
        WHERE evaluation_id = :evaluationId AND side = :side
        LIMIT 1
        """,
    )
    suspend fun findByEvaluationAndSide(evaluationId: Long, side: OrderSide): OrderEntity?

    @Query(
        """
        SELECT * FROM orders
        WHERE strategy_run_id = :strategyRunId
        ORDER BY id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<OrderEntity>

    @Query(
        """
        SELECT * FROM orders
        WHERE strategy_run_id = :strategyRunId AND status = :status
        ORDER BY evaluation_id ASC, id ASC
        """,
    )
    suspend fun findByRunAndStatus(
        strategyRunId: Long,
        status: OrderStatus,
    ): List<OrderEntity>

    @Query("SELECT COUNT(*) FROM orders WHERE strategy_run_id = :strategyRunId")
    suspend fun countByRun(strategyRunId: Long): Int
}
