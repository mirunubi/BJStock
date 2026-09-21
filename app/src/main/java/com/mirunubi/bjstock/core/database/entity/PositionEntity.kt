package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "positions",
    foreignKeys = [
        ForeignKey(
            entity = StrategyRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_run_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = InstrumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["instrument_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_run_id", "instrument_id"],
            unique = true,
            name = "uq_positions_run_instrument",
        ),
        Index(value = ["instrument_id"], name = "idx_positions_instrument_id"),
    ],
)
data class PositionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    val quantity: Long,
    @ColumnInfo(name = "average_price")
    val averagePrice: Long,
    @ColumnInfo(name = "realized_profit")
    val realizedProfit: Long = 0,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
)
