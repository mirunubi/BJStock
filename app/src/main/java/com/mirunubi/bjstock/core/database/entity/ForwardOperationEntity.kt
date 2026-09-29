package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import java.time.Instant
import java.time.LocalDate

/**
 * One row per Forward Test invocation. [trigger] says who started it (MANUAL / WORKER);
 * [operationKind] says what it does. Terminal rows are immutable.
 */
@Entity(
    tableName = "forward_operations",
    indices = [
        Index(value = ["operation_key"], unique = true, name = "uq_forward_operations_operation_key"),
        Index(value = ["started_at"], name = "idx_forward_operations_started"),
        Index(value = ["status"], name = "idx_forward_operations_status"),
    ],
)
data class ForwardOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "operation_key")
    val operationKey: String,
    val trigger: ForwardOperationTrigger,
    @ColumnInfo(name = "operation_kind", defaultValue = "'FORWARD_RUN'")
    val operationKind: ForwardOperationKind = ForwardOperationKind.FORWARD_RUN,
    @ColumnInfo(name = "work_id")
    val workId: String? = null,
    @ColumnInfo(name = "work_attempt")
    val workAttempt: Int? = null,
    @ColumnInfo(name = "through_date")
    val throughDate: LocalDate,
    val status: ForwardOperationStatus,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Instant? = null,
    @ColumnInfo(name = "final_code")
    val finalCode: String? = null,
    @ColumnInfo(name = "safe_message")
    val safeMessage: String? = null,
    @ColumnInfo(name = "runs_considered")
    val runsConsidered: Int = 0,
    @ColumnInfo(name = "runs_processed")
    val runsProcessed: Int = 0,
    @ColumnInfo(name = "runs_skipped")
    val runsSkipped: Int = 0,
    @ColumnInfo(name = "cycles_completed")
    val cyclesCompleted: Int = 0,
    @ColumnInfo(name = "cycles_failed")
    val cyclesFailed: Int = 0,
    @ColumnInfo(name = "elapsed_ms")
    val elapsedMs: Long? = null,
)
