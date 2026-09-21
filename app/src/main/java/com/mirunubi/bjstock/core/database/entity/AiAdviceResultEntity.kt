package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.AiRecommendation
import java.time.Instant

@Entity(
    tableName = "ai_advice_results",
    foreignKeys = [
        ForeignKey(
            entity = AiAdviceRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["request_id"], unique = true, name = "uq_ai_advice_results_request_id"),
    ],
)
data class AiAdviceResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "request_id")
    val requestId: Long,
    val recommendation: AiRecommendation,
    val confidence: Long? = null,
    val summary: String? = null,
    @ColumnInfo(name = "reasoning_summary")
    val reasoningSummary: String? = null,
    @ColumnInfo(name = "risk_notes")
    val riskNotes: String? = null,
    @ColumnInfo(name = "raw_response")
    val rawResponse: String? = null,
    @ColumnInfo(name = "used_in_decision")
    val usedInDecision: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
