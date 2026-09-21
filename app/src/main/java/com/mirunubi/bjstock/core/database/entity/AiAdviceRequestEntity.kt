package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "ai_advice_requests",
    foreignKeys = [
        ForeignKey(
            entity = StockEvaluationEntity::class,
            parentColumns = ["id"],
            childColumns = ["evaluation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["evaluation_id"], name = "idx_ai_advice_requests_evaluation_id"),
    ],
)
data class AiAdviceRequestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "evaluation_id")
    val evaluationId: Long,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "prompt_version")
    val promptVersion: String,
    @ColumnInfo(name = "request_payload")
    val requestPayload: String? = null,
    @ColumnInfo(name = "requested_at")
    val requestedAt: Instant,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
