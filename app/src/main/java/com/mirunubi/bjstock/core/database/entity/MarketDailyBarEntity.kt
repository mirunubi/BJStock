package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "market_daily_bars",
    foreignKeys = [
        ForeignKey(
            entity = InstrumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["instrument_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["instrument_id", "trade_date"],
            unique = true,
            name = "uq_market_daily_bars_instrument_trade_date",
        ),
    ],
)
data class MarketDailyBarEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    @ColumnInfo(name = "trade_date")
    val tradeDate: LocalDate,
    @ColumnInfo(name = "open_price")
    val openPrice: Long,
    @ColumnInfo(name = "high_price")
    val highPrice: Long,
    @ColumnInfo(name = "low_price")
    val lowPrice: Long,
    @ColumnInfo(name = "close_price")
    val closePrice: Long,
    val volume: Long,
    @ColumnInfo(name = "trading_value")
    val tradingValue: Long? = null,
    val source: String,
    @ColumnInfo(name = "collected_at")
    val collectedAt: Instant,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
