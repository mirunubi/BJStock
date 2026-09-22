package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "theme_instruments",
    foreignKeys = [
        ForeignKey(
            entity = ThemeEntity::class,
            parentColumns = ["id"],
            childColumns = ["theme_id"],
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
            value = ["theme_id", "instrument_id"],
            unique = true,
            name = "uq_theme_instruments_theme_instrument",
        ),
        Index(value = ["theme_id"], name = "idx_theme_instruments_theme"),
        Index(value = ["instrument_id"], name = "idx_theme_instruments_instrument"),
    ],
)
data class ThemeInstrumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "theme_id")
    val themeId: Long,
    @ColumnInfo(name = "instrument_id")
    val instrumentId: Long,
    val note: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
