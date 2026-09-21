package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "portfolio_daily_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = StrategyRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_run_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_run_id", "snapshot_date"],
            unique = true,
            name = "uq_portfolio_daily_snapshots_run_date",
        ),
    ],
)
data class PortfolioDailySnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "snapshot_date")
    val snapshotDate: LocalDate,
    val cash: Long,
    @ColumnInfo(name = "market_value")
    val marketValue: Long,
    @ColumnInfo(name = "total_asset")
    val totalAsset: Long,
    @ColumnInfo(name = "daily_profit")
    val dailyProfit: Long,
    @ColumnInfo(name = "daily_return")
    val dailyReturn: Long,
    @ColumnInfo(name = "cumulative_profit")
    val cumulativeProfit: Long,
    @ColumnInfo(name = "cumulative_return")
    val cumulativeReturn: Long,
    val drawdown: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
