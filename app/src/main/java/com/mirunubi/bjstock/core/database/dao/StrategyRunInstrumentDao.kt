package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.StrategyRunInstrumentEntity

@Dao
interface StrategyRunInstrumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: StrategyRunInstrumentEntity): Long

    @Query(
        """
        SELECT * FROM strategy_run_instruments
        WHERE strategy_run_id = :strategyRunId
        ORDER BY instrument_id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<StrategyRunInstrumentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM strategy_run_instruments
        WHERE strategy_run_id = :strategyRunId
        """,
    )
    suspend fun countByRun(strategyRunId: Long): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM strategy_run_instruments
            WHERE strategy_run_id = :strategyRunId AND instrument_id = :instrumentId
        )
        """,
    )
    suspend fun exists(strategyRunId: Long, instrumentId: Long): Boolean

    @Query(
        """
        DELETE FROM strategy_run_instruments
        WHERE strategy_run_id = :strategyRunId AND instrument_id = :instrumentId
        """,
    )
    suspend fun delete(strategyRunId: Long, instrumentId: Long): Int
}
