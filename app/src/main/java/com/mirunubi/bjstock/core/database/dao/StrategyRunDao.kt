package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyRunDao {
    @Query("SELECT COUNT(*) FROM strategy_runs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM strategy_runs")
    fun observeCount(): Flow<Int>
}
