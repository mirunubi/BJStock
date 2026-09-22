package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity

@Dao
interface StrategySignalRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: StrategySignalRuleEntity): Long

    @Update
    suspend fun update(entity: StrategySignalRuleEntity)

    @Query("DELETE FROM strategy_signal_rules WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM strategy_signal_rules WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): StrategySignalRuleEntity?

    @Query(
        """
        SELECT * FROM strategy_signal_rules
        WHERE strategy_version_id = :strategyVersionId
        ORDER BY priority ASC, id ASC
        """,
    )
    suspend fun findByVersion(strategyVersionId: Long): List<StrategySignalRuleEntity>

    @Query(
        """
        SELECT * FROM strategy_signal_rules
        WHERE strategy_version_id = :strategyVersionId AND enabled = 1
        ORDER BY priority ASC, id ASC
        """,
    )
    suspend fun findEnabledByVersion(strategyVersionId: Long): List<StrategySignalRuleEntity>
}
