package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "strategy_versions",
    foreignKeys = [
        ForeignKey(
            entity = StrategyEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_id", "version_no"],
            unique = true,
            name = "uq_strategy_versions_strategy_version",
        ),
    ],
)
data class StrategyVersionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_id")
    val strategyId: Long,
    @ColumnInfo(name = "version_no")
    val versionNo: Int,
    val description: String? = null,
    @ColumnInfo(name = "buy_threshold")
    val buyThreshold: Long,
    @ColumnInfo(name = "sell_threshold")
    val sellThreshold: Long,
    @ColumnInfo(name = "valid_from")
    val validFrom: LocalDate? = null,
    @ColumnInfo(name = "valid_to")
    val validTo: LocalDate? = null,
    val status: StrategyVersionStatus,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
