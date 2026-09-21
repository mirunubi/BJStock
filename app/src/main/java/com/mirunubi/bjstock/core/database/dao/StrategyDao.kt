package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyDao {
    @Query("SELECT COUNT(*) FROM strategies")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM strategies")
    fun observeCount(): Flow<Int>
}
