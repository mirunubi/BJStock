package com.mirunubi.bjstock.core.theme

import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.ThemeDao
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.database.entity.ThemeInstrumentEntity
import java.time.Instant

class ThemeService(
    private val themeDao: ThemeDao,
    private val instrumentDao: InstrumentDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun createTheme(name: String, description: String? = null): Long {
        val normalized = normalizeName(name)
        if (themeDao.findThemeByName(normalized) != null) {
            throw ThemeException("theme name already exists: $normalized")
        }
        return themeDao.insertTheme(
            ThemeEntity(
                name = normalized,
                description = description,
                isActive = true,
                createdAt = now(),
                updatedAt = now(),
            ),
        )
    }

    suspend fun renameTheme(themeId: Long, name: String) {
        val theme = requireTheme(themeId)
        val normalized = normalizeName(name)
        val existing = themeDao.findThemeByName(normalized)
        if (existing != null && existing.id != themeId) {
            throw ThemeException("theme name already exists: $normalized")
        }
        themeDao.updateTheme(theme.copy(name = normalized, updatedAt = now()))
    }

    suspend fun setActive(themeId: Long, active: Boolean) {
        val theme = requireTheme(themeId)
        themeDao.updateTheme(theme.copy(isActive = active, updatedAt = now()))
    }

    suspend fun addInstrument(themeId: Long, instrumentId: Long, note: String? = null) {
        requireTheme(themeId)
        instrumentDao.findById(instrumentId)
            ?: throw ThemeException("instrument not found: $instrumentId")
        if (themeDao.membershipExists(themeId, instrumentId)) {
            throw ThemeException("instrument already in theme")
        }
        themeDao.insertMembership(
            ThemeInstrumentEntity(
                themeId = themeId,
                instrumentId = instrumentId,
                note = note,
                createdAt = now(),
            ),
        )
    }

    suspend fun removeInstrument(themeId: Long, instrumentId: Long) {
        requireTheme(themeId)
        themeDao.deleteMembership(themeId, instrumentId)
    }

    suspend fun listThemes() = themeDao.findAllThemes()

    suspend fun listActiveThemes() = themeDao.findActiveThemes()

    suspend fun listInstrumentIds(themeId: Long): List<Long> =
        themeDao.findMemberships(themeId).map { it.instrumentId }.sorted()

    suspend fun countInstruments(themeId: Long): Int = themeDao.countMemberships(themeId)

    suspend fun findById(themeId: Long) = themeDao.findThemeById(themeId)

    private suspend fun requireTheme(themeId: Long): ThemeEntity =
        themeDao.findThemeById(themeId) ?: throw ThemeException("theme not found: $themeId")

    companion object {
        fun normalizeName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "theme name must not be blank" }
            return trimmed
        }
    }
}

class ThemeException(message: String) : IllegalStateException(message)
