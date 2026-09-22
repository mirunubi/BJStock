package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.PositionEntity

@Dao
interface PositionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PositionEntity): Long

    @Update
    suspend fun update(entity: PositionEntity)

    @Query(
        """
        SELECT * FROM positions
        WHERE strategy_run_id = :strategyRunId AND instrument_id = :instrumentId
        LIMIT 1
        """,
    )
    suspend fun find(strategyRunId: Long, instrumentId: Long): PositionEntity?

    @Query(
        """
        SELECT * FROM positions
        WHERE strategy_run_id = :strategyRunId
        ORDER BY instrument_id ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<PositionEntity>

    @Query(
        """
        SELECT * FROM positions
        WHERE strategy_run_id = :strategyRunId AND quantity > 0
        ORDER BY instrument_id ASC
        """,
    )
    suspend fun findOpenByRun(strategyRunId: Long): List<PositionEntity>
}
