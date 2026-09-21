package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import java.time.Instant

@Entity(
    tableName = "orders",
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
        ForeignKey(
            entity = StockEvaluationEntity::class,
            parentColumns = ["id"],
            childColumns = ["evaluation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["client_order_id"], unique = true, name = "uq_orders_client_order_id"),
        Index(value = ["strategy_run_id", "created_at"], name = "idx_orders_run_created"),
        Index(value = ["instrument_id", "created_at"], name = "idx_orders_instrument_created"),
        Index(value = ["evaluation_id"], name = "idx_orders_evaluation_id"),
    ],
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "client_order_id")
    val clientOrderId: String,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    @ColumnInfo(name = "evaluation_id")
    val evaluationId: Long? = null,
    val side: OrderSide,
    @ColumnInfo(name = "order_type")
    val orderType: OrderType,
    @ColumnInfo(name = "requested_price")
    val requestedPrice: Long? = null,
    val quantity: Long,
    val status: OrderStatus,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "executed_at")
    val executedAt: Instant? = null,
    @ColumnInfo(name = "cancelled_at")
    val cancelledAt: Instant? = null,
)
