package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.TradeDecision
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "stock_evaluations",
    foreignKeys = [
        ForeignKey(
            entity = StrategyRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_run_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = InstrumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["instrument_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_run_id", "instrument_id", "evaluation_date"],
            unique = true,
            name = "uq_stock_evaluations_run_instrument_date",
        ),
        Index(value = ["strategy_run_id", "evaluation_date"], name = "idx_stock_evaluations_run_eval_date"),
        Index(value = ["instrument_id", "evaluation_date"], name = "idx_stock_evaluations_instrument_eval_date"),
    ],
)
data class StockEvaluationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    @ColumnInfo(name = "evaluation_date")
    val evaluationDate: LocalDate,
    @ColumnInfo(name = "quant_score")
    val quantScore: Long,
    @ColumnInfo(name = "ai_score")
    val aiScore: Long? = null,
    @ColumnInfo(name = "final_score")
    val finalScore: Long,
    @ColumnInfo(name = "quant_decision")
    val quantDecision: TradeDecision,
    @ColumnInfo(name = "final_decision")
    val finalDecision: TradeDecision,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
