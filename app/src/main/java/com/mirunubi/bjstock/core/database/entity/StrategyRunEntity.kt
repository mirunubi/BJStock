package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "strategy_runs",
    foreignKeys = [
        ForeignKey(
            entity = StrategyVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["strategy_version_id"], name = "idx_strategy_runs_strategy_version"),
    ],
)
data class StrategyRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "run_name")
    val runName: String,
    @ColumnInfo(name = "strategy_version_id")
    val strategyVersionId: Long,
    @ColumnInfo(name = "run_type")
    val runType: RunType = RunType.PAPER,
    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,
    @ColumnInfo(name = "end_date")
    val endDate: LocalDate? = null,
    @ColumnInfo(name = "initial_cash")
    val initialCash: Long,
    val status: RunStatus,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant? = null,
    @ColumnInfo(name = "ended_at")
    val endedAt: Instant? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
)
