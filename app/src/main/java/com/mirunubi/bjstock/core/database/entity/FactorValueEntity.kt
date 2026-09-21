package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "factor_values",
    foreignKeys = [
        ForeignKey(
            entity = InstrumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["instrument_id"],
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
            value = ["instrument_id", "factor_id", "evaluation_date", "calculation_version"],
            unique = true,
            name = "uq_factor_values_instrument_factor_date_version",
        ),
        Index(value = ["instrument_id", "evaluation_date"], name = "idx_factor_values_instrument_eval_date"),
        Index(value = ["factor_id", "evaluation_date"], name = "idx_factor_values_factor_eval_date"),
    ],
)
data class FactorValueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    @ColumnInfo(name = "factor_id")
    val factorId: Long,
    @ColumnInfo(name = "evaluation_date")
    val evaluationDate: LocalDate,
    @ColumnInfo(name = "raw_value")
    val rawValue: String? = null,
    @ColumnInfo(name = "normalized_score")
    val normalizedScore: Long? = null,
    val source: String,
    @ColumnInfo(name = "calculation_version")
    val calculationVersion: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
