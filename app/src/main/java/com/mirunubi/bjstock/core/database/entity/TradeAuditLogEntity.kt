package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "trade_audit_logs",
    foreignKeys = [
        ForeignKey(
            entity = StrategyRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_run_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_key"], unique = true, name = "uq_trade_audit_logs_event_key"),
        Index(value = ["strategy_run_id", "market_date"], name = "idx_trade_audit_logs_run_date"),
        Index(value = ["strategy_run_id", "created_at"], name = "idx_trade_audit_logs_run_created"),
    ],
)
data class TradeAuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long? = null,
    @ColumnInfo(name = "evaluation_id")
    val evaluationId: Long? = null,
    @ColumnInfo(name = "order_id")
    val orderId: Long? = null,
    @ColumnInfo(name = "execution_id")
    val executionId: Long? = null,
    @ColumnInfo(name = "market_date")
    val marketDate: LocalDate? = null,
    @ColumnInfo(name = "event_type")
    val eventType: TradeAuditEventType,
    @ColumnInfo(name = "decision_source")
    val decisionSource: DecisionSource? = null,
    @ColumnInfo(name = "rule_id")
    val ruleId: Long? = null,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String? = null,
    @ColumnInfo(name = "reason_text")
    val reasonText: String? = null,
    @ColumnInfo(name = "metric_code")
    val metricCode: String? = null,
    @ColumnInfo(name = "observed_value")
    val observedValue: String? = null,
    @ColumnInfo(name = "threshold_value")
    val thresholdValue: String? = null,
    @ColumnInfo(name = "event_key")
    val eventKey: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
