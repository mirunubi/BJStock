package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "strategy_factor_weights",
    foreignKeys = [
        ForeignKey(
            entity = StrategyVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = FactorDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["factor_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_version_id", "factor_id"],
            unique = true,
            name = "uq_strategy_factor_weights_version_factor",
        ),
        Index(value = ["factor_id"], name = "idx_strategy_factor_weights_factor_id"),
    ],
)
data class StrategyFactorWeightEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_version_id")
    val strategyVersionId: Long,
    @ColumnInfo(name = "factor_id")
    val factorId: Long,
    val weight: Long,
    @ColumnInfo(name = "min_score")
    val minScore: Long? = null,
    @ColumnInfo(name = "max_score")
    val maxScore: Long? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "factor_calculation_version")
    val factorCalculationVersion: String,
)
