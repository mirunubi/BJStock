package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.InstrumentType
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "instruments",
    indices = [
        Index(value = ["market", "symbol"], unique = true, name = "uq_instruments_market_symbol"),
    ],
)
data class InstrumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val market: String,
    val symbol: String,
    val name: String,
    val sector: String? = null,
    val industry: String? = null,
    val currency: String = "KRW",
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "listed_date")
    val listedDate: LocalDate? = null,
    @ColumnInfo(name = "delisted_date")
    val delistedDate: LocalDate? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
    @ColumnInfo(name = "standard_code")
    val standardCode: String? = null,
    val board: Board = Board.OTHER,
    @ColumnInfo(name = "instrument_type")
    val instrumentType: InstrumentType = InstrumentType.OTHER,
)
