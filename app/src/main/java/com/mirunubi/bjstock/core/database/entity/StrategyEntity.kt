package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "strategies",
    indices = [
        Index(value = ["strategy_code"], unique = true, name = "uq_strategies_strategy_code"),
    ],
)
data class StrategyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_code")
    val strategyCode: String,
    @ColumnInfo(name = "strategy_name")
    val strategyName: String,
    val description: String? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
)
