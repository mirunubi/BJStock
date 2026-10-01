package com.mirunubi.bjstock.feature.stocks

import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationResult
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.theme.ThemeService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import java.time.LocalDate
import javax.inject.Inject

data class InstrumentSummary(
    val instrumentId: Long,
    val symbol: String,
    val name: String,
    val board: Board,
    val sector: String? = null,
    val industry: String? = null,
)

data class StoredBar(val tradeDate: LocalDate, val closePrice: Long, val volume: Long)

data class ThemeRef(val themeId: Long, val name: String)

/** Read-only access for the Stocks tab. Nothing here writes to the database. */
interface StocksDataSource {
    /** Active instruments whose symbol or name contains [query]. */
    suspend fun search(query: String): List<InstrumentSummary>

    /** Up to [limit] latest stored daily bars, oldest first. */
    suspend fun recentBars(instrumentId: Long, limit: Int): List<StoredBar>

    /** The six system factors as of [asOfDate], calculated for display only. */
    suspend fun factors(instrumentId: Long, asOfDate: LocalDate): List<FactorCalculationResult>

    suspend fun activeThemes(): List<ThemeRef>

    /** Active themes that contain the instrument. */
    suspend fun themesOf(instrumentId: Long): List<ThemeRef>

    /** Active member instruments of a theme. */
    suspend fun themeMembers(themeId: Long): List<InstrumentSummary>

    /** Network call to KIS; only ever made on an explicit user action. */
    suspend fun currentPrice(symbol: String): CurrentStockQuote
}

class RoomStocksDataSource @Inject constructor(
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val factorCalculationService: FactorCalculationService,
    private val themeService: ThemeService,
    private val kisMarketRepository: KisMarketRepository,
) : StocksDataSource {
    override suspend fun search(query: String): List<InstrumentSummary> =
        instrumentDao.searchActive("%${query.trim()}%", SEARCH_LIMIT).map(::summary)

    override suspend fun recentBars(instrumentId: Long, limit: Int): List<StoredBar> {
        val latest = marketDailyBarDao.findLatest(instrumentId) ?: return emptyList()
        return marketDailyBarDao.findBarsUpToDate(instrumentId, latest.tradeDate, limit)
            .reversed()
            .map { StoredBar(it.tradeDate, it.closePrice, it.volume) }
    }

    /** `persist = false`: viewing a stock never creates or updates `factor_values`. */
    override suspend fun factors(instrumentId: Long, asOfDate: LocalDate): List<FactorCalculationResult> =
        factorCalculationService.calculateAllSystemFactors(instrumentId, asOfDate, persist = false)

    override suspend fun activeThemes(): List<ThemeRef> =
        themeService.listActiveThemes().map { ThemeRef(it.id, it.name) }

    override suspend fun themesOf(instrumentId: Long): List<ThemeRef> =
        themeService.listActiveThemes()
            .filter { instrumentId in themeService.listInstrumentIds(it.id) }
            .map { ThemeRef(it.id, it.name) }

    override suspend fun themeMembers(themeId: Long): List<InstrumentSummary> =
        themeService.listInstrumentIds(themeId)
            .mapNotNull { instrumentDao.findById(it) }
            .filter { it.isActive }
            .sortedBy { it.symbol }
            .map(::summary)

    override suspend fun currentPrice(symbol: String): CurrentStockQuote =
        kisMarketRepository.inquireCurrentPrice(symbol)

    private fun summary(entity: InstrumentEntity) = InstrumentSummary(
        instrumentId = entity.id,
        symbol = entity.symbol,
        name = entity.name,
        board = entity.board,
        sector = entity.sector,
        industry = entity.industry,
    )

    private companion object {
        const val SEARCH_LIMIT = 30
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class StocksModule {
    @Binds
    abstract fun bindStocksDataSource(source: RoomStocksDataSource): StocksDataSource
}
