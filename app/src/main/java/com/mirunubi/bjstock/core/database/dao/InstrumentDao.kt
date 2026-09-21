package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.model.Board
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface InstrumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(instrument: InstrumentEntity): Long

    @Update
    suspend fun update(instrument: InstrumentEntity)

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

    @Query(
        """
        SELECT COUNT(*) FROM instruments
        WHERE market = :market AND board = :board AND is_active = 1
        """,
    )
    suspend fun countActiveByMarketAndBoard(market: String, board: Board): Int

    @Query(
        """
        SELECT COUNT(*) FROM instruments
        WHERE market = :market AND board = :board AND is_active = 1
        """,
    )
    fun observeActiveCountByMarketAndBoard(market: String, board: Board): Flow<Int>

    @Query(
        """
        SELECT * FROM instruments
        WHERE market = :market AND board = :board AND is_active = 1
        """,
    )
    suspend fun findActiveByMarketAndBoard(market: String, board: Board): List<InstrumentEntity>

    @Query(
        """
        SELECT * FROM instruments
        WHERE is_active = 1
          AND (symbol LIKE :pattern OR name LIKE :pattern)
        ORDER BY symbol
        LIMIT :limit
        """,
    )
    suspend fun searchActive(pattern: String, limit: Int = 50): List<InstrumentEntity>

    @Query("UPDATE instruments SET is_active = 0, updated_at = :updatedAt WHERE id = :id")
    suspend fun deactivateById(id: Long, updatedAt: Instant)
}
