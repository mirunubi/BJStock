package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import java.time.Instant

@Entity(
    tableName = "api_error_logs",
    indices = [
        Index(value = ["occurred_at"], name = "idx_api_error_logs_occurred"),
    ],
)
data class ApiErrorLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val provider: ApiErrorProvider,
    val operation: String,
    @ColumnInfo(name = "error_type")
    val errorType: ApiErrorType,
    @ColumnInfo(name = "http_status")
    val httpStatus: Int? = null,
    @ColumnInfo(name = "business_code")
    val businessCode: String? = null,
    @ColumnInfo(name = "safe_message")
    val safeMessage: String,
    val retryable: Boolean = false,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long? = null,
    @ColumnInfo(name = "forward_cycle_id")
    val forwardCycleId: Long? = null,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant = Instant.now(),
)
