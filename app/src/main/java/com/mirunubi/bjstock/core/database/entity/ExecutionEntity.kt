package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "executions",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["order_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["order_id"], name = "idx_executions_order_id"),
    ],
)
data class ExecutionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "order_id")
    val orderId: Long,
    @ColumnInfo(name = "execution_price")
    val executionPrice: Long,
    val quantity: Long,
    val commission: Long = 0,
    val tax: Long = 0,
    val slippage: Long = 0,
    @ColumnInfo(name = "executed_at")
    val executedAt: Instant,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
