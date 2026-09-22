package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "forward_test_cycles",
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
            value = ["strategy_run_id", "market_date"],
            unique = true,
            name = "uq_forward_test_cycles_run_date",
        ),
        Index(value = ["strategy_run_id", "status"], name = "idx_forward_test_cycles_run_status"),
    ],
)
data class ForwardTestCycleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "market_date")
    val marketDate: LocalDate,
    val status: ForwardCycleStatus,
    @ColumnInfo(name = "current_stage")
    val currentStage: ForwardCycleStage,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    val retryable: Boolean = false,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant? = null,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
)
