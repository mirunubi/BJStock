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

    @Query(
        """
        SELECT * FROM market_daily_bars
        WHERE instrument_id = :instrumentId
          AND trade_date <= :asOfDate
        ORDER BY trade_date DESC
        LIMIT :limit
        """,
    )
    suspend fun findBarsUpToDate(
        instrumentId: Long,
        asOfDate: LocalDate,
        limit: Int,
    ): List<MarketDailyBarEntity>

    @Query(
        """
        DELETE FROM market_daily_bars
        WHERE instrument_id = :instrumentId AND trade_date = :tradeDate
        """,
    )
    suspend fun deleteByInstrumentAndDate(instrumentId: Long, tradeDate: LocalDate): Int

    @Query(
        """
        SELECT * FROM market_daily_bars
        WHERE instrument_id = :instrumentId
          AND trade_date > :afterDate
        ORDER BY trade_date ASC
        LIMIT 1
        """,
    )
    suspend fun findNextTradingBar(
        instrumentId: Long,
        afterDate: LocalDate,
    ): MarketDailyBarEntity?

    @Query(
        """
        SELECT * FROM market_daily_bars
        WHERE instrument_id = :instrumentId
          AND trade_date > :afterDate
          AND trade_date <= :asOfDate
        ORDER BY trade_date ASC
        LIMIT 1
        """,
    )
    suspend fun findNextTradingBarAsOf(
        instrumentId: Long,
        afterDate: LocalDate,
        asOfDate: LocalDate,
    ): MarketDailyBarEntity?

    @Query(
        """
        SELECT DISTINCT trade_date FROM market_daily_bars
        WHERE instrument_id IN (:instrumentIds)
          AND trade_date > :afterDate
          AND trade_date <= :throughDate
        ORDER BY trade_date ASC
        """,
    )
    suspend fun findDistinctTradeDatesInRange(
        instrumentIds: List<Long>,
        afterDate: LocalDate,
        throughDate: LocalDate,
    ): List<LocalDate>

    @Query(
        """
        SELECT COUNT(*) FROM market_daily_bars
        WHERE instrument_id = :instrumentId
          AND trade_date < :beforeDate
        """,
    )
    suspend fun countBarsBefore(instrumentId: Long, beforeDate: LocalDate): Int
}
