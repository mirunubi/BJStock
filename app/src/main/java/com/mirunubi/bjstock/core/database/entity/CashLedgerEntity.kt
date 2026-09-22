package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "cash_ledger",
    foreignKeys = [
        ForeignKey(
            entity = StrategyRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_run_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["strategy_run_id", "id"], name = "idx_cash_ledger_run_created"),
        Index(value = ["strategy_run_id", "event_date"], name = "idx_cash_ledger_run_event_date"),
        Index(
            value = ["strategy_run_id", "event_type"],
            name = "idx_cash_ledger_run_event_type",
        ),
    ],
)
data class CashLedgerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "event_type")
    val eventType: CashLedgerEventType,
    val amount: Long,
    @ColumnInfo(name = "balance_after")
    val balanceAfter: Long,
    @ColumnInfo(name = "reference_type")
    val referenceType: String? = null,
    @ColumnInfo(name = "reference_id")
    val referenceId: Long? = null,
    @ColumnInfo(name = "event_date")
    val eventDate: LocalDate,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
