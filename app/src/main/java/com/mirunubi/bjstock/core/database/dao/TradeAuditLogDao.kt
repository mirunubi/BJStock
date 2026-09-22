package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity

@Dao
interface TradeAuditLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TradeAuditLogEntity): Long

    @Query("SELECT * FROM trade_audit_logs WHERE event_key = :eventKey LIMIT 1")
    suspend fun findByEventKey(eventKey: String): TradeAuditLogEntity?

    @Query(
        """
        SELECT * FROM trade_audit_logs
        WHERE strategy_run_id = :strategyRunId
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<TradeAuditLogEntity>

    @Query(
        """
        SELECT * FROM trade_audit_logs
        WHERE strategy_run_id = :strategyRunId
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun findRecentByRun(strategyRunId: Long, limit: Int = 100): List<TradeAuditLogEntity>

    @Query("SELECT COUNT(*) FROM trade_audit_logs")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM trade_audit_logs WHERE strategy_run_id = :strategyRunId")
    suspend fun countByRun(strategyRunId: Long): Int
}
