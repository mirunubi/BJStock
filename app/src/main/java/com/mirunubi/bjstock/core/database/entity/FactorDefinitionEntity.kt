package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.FactorCategory
import com.mirunubi.bjstock.core.model.FactorValueType
import java.time.Instant

@Entity(
    tableName = "factor_definitions",
    indices = [
        Index(value = ["factor_code"], unique = true, name = "uq_factor_definitions_factor_code"),
    ],
)
data class FactorDefinitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "factor_code")
    val factorCode: String,
    @ColumnInfo(name = "factor_name")
    val factorName: String,
    val category: FactorCategory,
    val description: String? = null,
    @ColumnInfo(name = "value_type")
    val valueType: FactorValueType,
    @ColumnInfo(name = "higher_is_better")
    val higherIsBetter: Boolean,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
)
