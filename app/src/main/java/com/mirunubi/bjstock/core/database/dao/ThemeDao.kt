package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.database.entity.ThemeInstrumentEntity

@Dao
interface ThemeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTheme(entity: ThemeEntity): Long

    @Update
    suspend fun updateTheme(entity: ThemeEntity)

    @Query("SELECT * FROM themes WHERE id = :id LIMIT 1")
    suspend fun findThemeById(id: Long): ThemeEntity?

    @Query("SELECT * FROM themes WHERE name = :name LIMIT 1")
    suspend fun findThemeByName(name: String): ThemeEntity?

    @Query("SELECT * FROM themes ORDER BY name ASC")
    suspend fun findAllThemes(): List<ThemeEntity>

    @Query("SELECT * FROM themes WHERE is_active = 1 ORDER BY name ASC")
    suspend fun findActiveThemes(): List<ThemeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembership(entity: ThemeInstrumentEntity): Long

    @Query(
        """
        SELECT * FROM theme_instruments
        WHERE theme_id = :themeId
        ORDER BY instrument_id ASC
        """,
    )
    suspend fun findMemberships(themeId: Long): List<ThemeInstrumentEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM theme_instruments
            WHERE theme_id = :themeId AND instrument_id = :instrumentId
        )
        """,
    )
    suspend fun membershipExists(themeId: Long, instrumentId: Long): Boolean

    @Query(
        """
        DELETE FROM theme_instruments
        WHERE theme_id = :themeId AND instrument_id = :instrumentId
        """,
    )
    suspend fun deleteMembership(themeId: Long, instrumentId: Long): Int

    @Query("SELECT COUNT(*) FROM theme_instruments WHERE theme_id = :themeId")
    suspend fun countMemberships(themeId: Long): Int
}
