package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import java.time.LocalDate

@Dao
interface MarketDailyBarDao {
    @Insert
    suspend fun insert(entity: MarketDailyBarEntity): Long

    @Insert
    suspend fun insertAll(entities: List<MarketDailyBarEntity>): List<Long>

    @Update
    suspend fun update(entity: MarketDailyBarEntity)

    @Query(
        """
        SELECT * FROM market_daily_bars
        WHERE instrument_id = :instrumentId AND trade_date = :tradeDate
        LIMIT 1
        """,
    )
    suspend fun findByInstrumentAndDate(
        instrumentId: Long,
        tradeDate: LocalDate,
    ): MarketDailyBarEntity?

    @Query(
        """
        SELECT * FROM market_daily_bars
        WHERE instrument_id = :instrumentId
          AND trade_date >= :startDate
          AND trade_date <= :endDate
        ORDER BY trade_date ASC
        """,
    )
    suspend fun findByInstrumentAndDateRange(
        instrumentId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<MarketDailyBarEntity>

    @Query(
        """
        SELECT * FROM market_daily_bars
        WHERE instrument_id = :instrumentId
        ORDER BY trade_date DESC
        LIMIT 1
        """,
    )
    suspend fun findLatest(instrumentId: Long): MarketDailyBarEntity?

    @Query("SELECT COUNT(*) FROM market_daily_bars WHERE instrument_id = :instrumentId")
    suspend fun countByInstrument(instrumentId: Long): Int

    @Query("SELECT COUNT(*) FROM market_daily_bars")
    suspend fun count(): Int
}
