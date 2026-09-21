package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstrumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(instrument: InstrumentEntity): Long

    @Query("SELECT COUNT(*) FROM instruments")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM instruments")
    fun observeCount(): Flow<Int>
}
