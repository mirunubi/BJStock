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

    @Query("SELECT * FROM instruments WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): InstrumentEntity?

    @Query("SELECT * FROM instruments WHERE market = :market AND symbol = :symbol LIMIT 1")
    suspend fun findByMarketAndSymbol(market: String, symbol: String): InstrumentEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM instruments WHERE market = :market AND symbol = :symbol)")
    suspend fun existsByMarketAndSymbol(market: String, symbol: String): Boolean

    @Query("SELECT COUNT(*) FROM instruments")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM instruments")
    fun observeCount(): Flow<Int>
}
