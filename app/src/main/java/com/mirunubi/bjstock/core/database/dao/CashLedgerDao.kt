package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.CashLedgerEntity
import com.mirunubi.bjstock.core.model.CashLedgerEventType

@Dao
interface CashLedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CashLedgerEntity): Long

    @Query(
        """
        SELECT * FROM cash_ledger
        WHERE strategy_run_id = :strategyRunId
        ORDER BY id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<CashLedgerEntity>

    @Query(
        """
        SELECT * FROM cash_ledger
        WHERE strategy_run_id = :strategyRunId
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun findLatest(strategyRunId: Long): CashLedgerEntity?

    @Query(
        """
        SELECT COUNT(*) FROM cash_ledger
        WHERE strategy_run_id = :strategyRunId AND event_type = :eventType
        """,
    )
    suspend fun countByRunAndType(strategyRunId: Long, eventType: CashLedgerEventType): Int

    @Query("SELECT COUNT(*) FROM cash_ledger WHERE strategy_run_id = :strategyRunId")
    suspend fun countByRun(strategyRunId: Long): Int
}
