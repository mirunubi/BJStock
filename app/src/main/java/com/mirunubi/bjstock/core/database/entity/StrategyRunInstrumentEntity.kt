package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "strategy_run_instruments",
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
            name = "uq_strategy_run_instruments_run_instrument",
        ),
        Index(value = ["strategy_run_id"], name = "idx_strategy_run_instruments_run"),
        Index(value = ["instrument_id"], name = "idx_strategy_run_instruments_instrument"),
    ],
)
data class StrategyRunInstrumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
