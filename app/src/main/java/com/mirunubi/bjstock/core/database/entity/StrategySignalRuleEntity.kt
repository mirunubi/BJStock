package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import java.time.Instant

@Entity(
    tableName = "strategy_signal_rules",
    foreignKeys = [
        ForeignKey(
            entity = StrategyVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_version_id", "rule_code"],
            unique = true,
            name = "uq_strategy_signal_rules_version_code",
        ),
        Index(value = ["strategy_version_id"], name = "idx_strategy_signal_rules_version"),
    ],
)
data class StrategySignalRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_version_id")
    val strategyVersionId: Long,
    @ColumnInfo(name = "rule_code")
    val ruleCode: String,
    @ColumnInfo(name = "metric_code")
    val metricCode: SignalMetricCode,
    val operator: SignalOperator,
    @ColumnInfo(name = "threshold_value")
    val thresholdValue: String,
    val action: SignalAction,
    val priority: Int,
    val enabled: Boolean = true,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String = "v1",
    val description: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
