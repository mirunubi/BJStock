package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.Instant
import java.time.LocalDate

/**
 * Append-only operational evidence. [operationId] is null only for WORKER_SCHEDULE_CHANGED.
 * run / cycle / instrument ids are soft references.
 */
@Entity(
    tableName = "operational_events",
    foreignKeys = [
        ForeignKey(
            entity = ForwardOperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_key"], unique = true, name = "uq_operational_events_event_key"),
        Index(value = ["operation_id"], name = "idx_operational_events_operation"),
        Index(value = ["run_id", "created_at"], name = "idx_operational_events_run_created"),
        Index(value = ["created_at"], name = "idx_operational_events_created"),
    ],
)
data class OperationalEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "event_key")
    val eventKey: String,
    @ColumnInfo(name = "operation_id")
    val operationId: Long? = null,
    @ColumnInfo(name = "run_id")
    val runId: Long? = null,
    @ColumnInfo(name = "cycle_id")
    val cycleId: Long? = null,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long? = null,
    @ColumnInfo(name = "market_date")
    val marketDate: LocalDate? = null,
    @ColumnInfo(name = "event_type")
    val eventType: OperationalEventType,
    val result: String? = null,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String? = null,
    @ColumnInfo(name = "safe_message")
    val safeMessage: String? = null,
    @ColumnInfo(name = "elapsed_ms")
    val elapsedMs: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
