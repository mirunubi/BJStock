package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.PaperTradingPolicyEntity

@Dao
interface PaperTradingPolicyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PaperTradingPolicyEntity): Long

    @Query(
        """
        SELECT * FROM paper_trading_policies
        WHERE strategy_run_id = :strategyRunId
        LIMIT 1
        """,
    )
    suspend fun findByRun(strategyRunId: Long): PaperTradingPolicyEntity?

    @Query("SELECT COUNT(*) FROM paper_trading_policies WHERE strategy_run_id = :strategyRunId")
    suspend fun countByRun(strategyRunId: Long): Int
}
