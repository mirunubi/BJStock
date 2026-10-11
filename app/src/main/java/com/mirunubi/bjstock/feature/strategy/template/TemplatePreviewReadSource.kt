package com.mirunubi.bjstock.feature.strategy.template

import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.theme.ThemeService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject

data class InstrumentOption(val instrumentId: Long, val symbol: String, val name: String)

data class ThemeOption(val themeId: Long, val name: String, val memberCount: Int)

/** Read-only lookups for the template preview pickers. Nothing here writes, snapshots or creates a Run. */
interface TemplatePreviewReadSource {
    /** Active instruments whose symbol or name contains [query]. */
    suspend fun searchStocks(query: String): List<InstrumentOption>

    /** Active themes with their registered member count. */
    suspend fun themes(): List<ThemeOption>
}

class RoomTemplatePreviewReadSource @Inject constructor(
    private val instrumentDao: InstrumentDao,
    private val themeService: ThemeService,
) : TemplatePreviewReadSource {
    override suspend fun searchStocks(query: String): List<InstrumentOption> =
        instrumentDao.searchActive("%${query.trim()}%", SEARCH_LIMIT)
            .map { InstrumentOption(it.id, it.symbol, it.name) }

    override suspend fun themes(): List<ThemeOption> =
        themeService.listActiveThemes().map { ThemeOption(it.id, it.name, themeService.countInstruments(it.id)) }

    private companion object {
        const val SEARCH_LIMIT = 30
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class TemplatePreviewModule {
    @Binds
    abstract fun bindTemplatePreviewReadSource(source: RoomTemplatePreviewReadSource): TemplatePreviewReadSource
}
