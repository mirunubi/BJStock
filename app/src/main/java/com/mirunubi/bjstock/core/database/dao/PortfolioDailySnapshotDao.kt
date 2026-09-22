package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.PortfolioDailySnapshotEntity
import java.time.LocalDate

@Dao
interface PortfolioDailySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PortfolioDailySnapshotEntity): Long

    @Query(
        """
        SELECT * FROM portfolio_daily_snapshots
        WHERE strategy_run_id = :strategyRunId AND snapshot_date = :snapshotDate
        LIMIT 1
        """,
    )
    suspend fun find(strategyRunId: Long, snapshotDate: LocalDate): PortfolioDailySnapshotEntity?

    @Query(
        """
        SELECT * FROM portfolio_daily_snapshots
        WHERE strategy_run_id = :strategyRunId
        ORDER BY snapshot_date ASC
        """,
    )
    suspend fun findByRun(strategyRunId: Long): List<PortfolioDailySnapshotEntity>

    @Query(
        """
        SELECT * FROM portfolio_daily_snapshots
        WHERE strategy_run_id = :strategyRunId
        ORDER BY snapshot_date DESC
        LIMIT 1
        """,
    )
    suspend fun findLatest(strategyRunId: Long): PortfolioDailySnapshotEntity?

    @Query(
        """
        SELECT MAX(total_asset) FROM portfolio_daily_snapshots
        WHERE strategy_run_id = :strategyRunId AND snapshot_date <= :asOfDate
        """,
    )
    suspend fun findPeakTotalAsset(strategyRunId: Long, asOfDate: LocalDate): Long?
}
