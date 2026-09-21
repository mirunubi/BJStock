package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "stock_evaluation_details",
    foreignKeys = [
        ForeignKey(
            entity = StockEvaluationEntity::class,
            parentColumns = ["id"],
            childColumns = ["evaluation_id"],
            onDelete = ForeignKey.CASCADE,
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
            value = ["evaluation_id", "factor_id"],
            unique = true,
            name = "uq_stock_evaluation_details_evaluation_factor",
        ),
        Index(value = ["factor_id"], name = "idx_stock_evaluation_details_factor_id"),
    ],
)
data class StockEvaluationDetailEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "evaluation_id")
    val evaluationId: Long,
    @ColumnInfo(name = "factor_id")
    val factorId: Long,
    @ColumnInfo(name = "raw_value")
    val rawValue: String? = null,
    @ColumnInfo(name = "factor_score")
    val factorScore: Long,
    val weight: Long,
    @ColumnInfo(name = "weighted_score")
    val weightedScore: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
