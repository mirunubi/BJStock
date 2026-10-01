package com.mirunubi.bjstock.feature.stocks

import com.mirunubi.bjstock.core.factor.FactorCalculationResult
import com.mirunubi.bjstock.core.factor.FactorCalculationStatus
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.model.Board
import java.math.BigDecimal
import java.time.LocalDate

object StocksFixtures {
    val SAMSUNG = InstrumentSummary(instrumentId = 11, symbol = "005930", name = "삼성전자", board = Board.KOSPI)
    val HYNIX = InstrumentSummary(instrumentId = 12, symbol = "000660", name = "SK하이닉스", board = Board.KOSPI)
    val ECOPRO_BM = InstrumentSummary(instrumentId = 13, symbol = "247540", name = "에코프로비엠", board = Board.KOSDAQ)

    /** Consecutive calendar days from [start], oldest first. */
    fun bars(start: LocalDate, closes: List<Long>): List<StoredBar> =
        closes.mapIndexed { index, close -> StoredBar(start.plusDays(index.toLong()), close, 1_000L + index) }

    fun factor(code: String, asOf: LocalDate, raw: String, score: String) = FactorCalculationResult(
        factorCode = code,
        instrumentId = SAMSUNG.instrumentId,
        asOfDate = asOf,
        status = FactorCalculationStatus.SUCCESS,
        rawValue = BigDecimal(raw),
        normalizedScore = BigDecimal(score),
        calculationVersion = "v1",
    )

    fun allFactors(asOf: LocalDate) = FactorCodes.SYSTEM.map { factor(it, asOf, "1.5", "55") }

    fun quote(symbol: String) = CurrentStockQuote(
        symbol = symbol,
        currentPrice = 276_000,
        previousCloseDifference = -1_000,
        changeRate = -360_000,
        openPrice = 277_000,
        highPrice = 278_000,
        lowPrice = 275_000,
        volume = 1_000_000,
        tradingValue = null,
        businessDate = LocalDate.of(2026, 10, 1),
        source = "KIS",
    )
}

/** In-memory [StocksDataSource] that records every call. */
class FakeStocksDataSource : StocksDataSource {
    val instruments = mutableListOf(StocksFixtures.SAMSUNG, StocksFixtures.HYNIX, StocksFixtures.ECOPRO_BM)
    val bars = mutableMapOf<Long, List<StoredBar>>()
    val themes = mutableListOf<ThemeRef>()
    val members = mutableMapOf<Long, List<Long>>()
    var quoteFailure: Exception? = null
    var searchFailure: Exception? = null

    val searchCalls = mutableListOf<String>()
    val factorCalls = mutableListOf<Pair<Long, LocalDate>>()
    val quoteCalls = mutableListOf<String>()

    override suspend fun search(query: String): List<InstrumentSummary> {
        searchCalls += query
        searchFailure?.let { throw it }
        return instruments.filter { query in it.symbol || query in it.name }
    }

    override suspend fun recentBars(instrumentId: Long, limit: Int): List<StoredBar> =
        bars[instrumentId].orEmpty().takeLast(limit)

    override suspend fun factors(instrumentId: Long, asOfDate: LocalDate): List<FactorCalculationResult> {
        factorCalls += instrumentId to asOfDate
        return StocksFixtures.allFactors(asOfDate)
    }

    override suspend fun activeThemes(): List<ThemeRef> = themes.toList()

    override suspend fun themesOf(instrumentId: Long): List<ThemeRef> =
        themes.filter { instrumentId in members[it.themeId].orEmpty() }

    override suspend fun themeMembers(themeId: Long): List<InstrumentSummary> =
        members[themeId].orEmpty().mapNotNull { id -> instruments.firstOrNull { it.instrumentId == id } }

    override suspend fun currentPrice(symbol: String): CurrentStockQuote {
        quoteCalls += symbol
        quoteFailure?.let { throw it }
        return StocksFixtures.quote(symbol)
    }
}
