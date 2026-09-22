package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.model.RunStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyRunDao {
    @Query("SELECT COUNT(*) FROM strategy_runs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM strategy_runs")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: StrategyRunEntity): Long

    @Query("SELECT * FROM strategy_runs WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): StrategyRunEntity?

    @Query("SELECT * FROM strategy_runs ORDER BY id")
    suspend fun findAll(): List<StrategyRunEntity>

    @Query("UPDATE strategy_runs SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: RunStatus, updatedAt: Instant)
}
