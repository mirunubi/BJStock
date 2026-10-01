package com.mirunubi.bjstock.feature.stocks

import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.factor.FactorCalculationResult
import com.mirunubi.bjstock.core.factor.FactorCalculationStatus
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.SystemFactorCatalog
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.FactorValueType
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

data class StocksUiState(
    val query: String = "",
    val search: SearchState = SearchState.Idle,
    val themes: List<ThemeChip> = emptyList(),
    val themeBrowse: ThemeBrowse? = null,
    val detail: DetailState? = null,
)

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Results(val rows: List<InstrumentRow>) : SearchState
    data object NoResults : SearchState
    data class Failed(val message: String) : SearchState
}

/** One instrument line: `삼성전자` over `005930 · KOSPI`. [instrument] is kept for selection only. */
data class InstrumentRow(val instrument: InstrumentSummary, val name: String, val subtitle: String)

data class ThemeChip(val themeId: Long, val name: String)

data class ThemeBrowse(val theme: ThemeChip, val members: MembersState)

sealed interface MembersState {
    data object Loading : MembersState
    data class Loaded(val rows: List<InstrumentRow>) : MembersState
    data object Empty : MembersState
    data class Failed(val message: String) : MembersState
}

data class StockHeader(val name: String, val subtitle: String, val metadata: List<String>)

sealed interface DetailState {
    val instrument: InstrumentSummary
    val header: StockHeader

    data class Loading(override val instrument: InstrumentSummary, override val header: StockHeader) : DetailState

    data class Failed(
        override val instrument: InstrumentSummary,
        override val header: StockHeader,
        val message: String,
    ) : DetailState

    data class Content(
        override val instrument: InstrumentSummary,
        override val header: StockHeader,
        val price: PriceSummary?,
        val chart: ChartState,
        val factors: FactorPanel,
        val themes: ThemesSection,
        val quote: QuoteState = QuoteState.Idle,
    ) : DetailState
}

enum class PriceDirection { UP, DOWN, FLAT, UNKNOWN }

/** Latest stored daily bar. [change] is `—` when there is no previous stored bar. */
data class PriceSummary(
    val close: String,
    val tradeDate: String,
    val change: String,
    val direction: PriceDirection,
    val volume: String,
)

data class ChartPoint(val tradeDate: LocalDate, val closePrice: Long)

sealed interface ChartState {
    data class Empty(val message: String) : ChartState

    data class Line(
        val points: List<ChartPoint>,
        val caption: String,
        val startDate: String,
        val endDate: String,
        val high: String,
        val low: String,
        val latest: String,
    ) : ChartState
}

data class FactorRow(
    val name: String,
    val code: String,
    val rawValue: String?,
    val score: String?,
    val unavailable: String?,
)

sealed interface FactorPanel {
    data class Rows(val asOf: String, val rows: List<FactorRow>) : FactorPanel
    data class Unavailable(val message: String) : FactorPanel
}

sealed interface ThemesSection {
    data class Loaded(val chips: List<ThemeChip>) : ThemesSection
    data class Failed(val message: String) : ThemesSection
}

sealed interface QuoteState {
    data object Idle : QuoteState
    data object Loading : QuoteState
    data class Loaded(val card: QuoteCard) : QuoteState
    data class Failed(val message: String) : QuoteState
}

data class QuoteCard(
    val price: String,
    val change: String,
    val direction: PriceDirection,
    val open: String,
    val high: String,
    val low: String,
    val volume: String,
    val businessDate: String?,
    val fetchedAt: String,
)

/** Display-only formatting for the Stocks tab. Nothing computed here is persisted. */
object StocksPresenter {
    const val GUIDE = "종목명이나 종목코드를 검색해 보세요."
    const val NO_RESULTS = "일치하는 종목이 없습니다."
    const val SEARCH_FAILED = "종목을 검색하지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val DETAIL_FAILED = "종목 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val NO_BARS = "저장된 시세 데이터가 없습니다."
    const val CHART_TOO_SHORT = "차트를 그리려면 저장된 일봉이 2개 이상 필요합니다."
    const val FACTOR_INSUFFICIENT = "계산에 필요한 과거 데이터가 부족합니다."
    const val FACTOR_NO_DATA = "저장된 시세 데이터가 없어 계산할 수 없습니다."
    const val FACTOR_INVALID = "저장된 시세 데이터에 문제가 있어 계산할 수 없습니다."
    const val FACTOR_FAILED = "팩터를 계산하지 못했습니다."
    const val THEMES_NONE = "포함된 테마가 없습니다."
    const val THEMES_FAILED = "테마 정보를 불러오지 못했습니다."
    const val MEMBERS_NONE = "이 테마에 등록된 종목이 없습니다."
    const val MEMBERS_FAILED = "테마 종목을 불러오지 못했습니다."
    const val QUOTE_FAILED = "현재가를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요."
    const val NOT_AVAILABLE = "—"

    fun row(instrument: InstrumentSummary) = InstrumentRow(instrument, instrument.name, subtitle(instrument))

    fun header(instrument: InstrumentSummary) = StockHeader(
        name = instrument.name,
        subtitle = subtitle(instrument),
        metadata = listOfNotNull(
            instrument.sector?.takeIf { it.isNotBlank() }?.let { "업종 ${it.trim()}" },
            instrument.industry?.takeIf { it.isNotBlank() }?.let { "산업 ${it.trim()}" },
        ),
    )

    fun boardLabel(board: Board): String? = when (board) {
        Board.KOSPI -> "KOSPI"
        Board.KOSDAQ -> "KOSDAQ"
        Board.OTHER -> null
    }

    /** [bars] oldest first. Null when nothing is stored. */
    fun price(bars: List<StoredBar>): PriceSummary? {
        val latest = bars.lastOrNull() ?: return null
        val previous = bars.getOrNull(bars.size - 2)
        val percent = previous?.let { dailyChangePercent(latest.closePrice, it.closePrice) }
        return PriceSummary(
            close = won(latest.closePrice),
            tradeDate = date(latest.tradeDate),
            change = if (percent == null || previous == null) {
                NOT_AVAILABLE
            } else {
                "${signedWon(latest.closePrice - previous.closePrice)} (${signedPercent(percent)})"
            },
            direction = direction(percent),
            volume = shares(latest.volume),
        )
    }

    /** Presentation arithmetic: latest stored close vs the previous stored trading close, 2 decimals. */
    fun dailyChangePercent(latestClose: Long, previousClose: Long): BigDecimal? {
        if (previousClose <= 0) return null
        return BigDecimal.valueOf(latestClose - previousClose)
            .multiply(HUNDRED)
            .divide(BigDecimal.valueOf(previousClose), 2, RoundingMode.HALF_UP)
    }

    /** [bars] oldest first; the chart keeps that chronological order. */
    fun chart(bars: List<StoredBar>): ChartState {
        if (bars.isEmpty()) return ChartState.Empty(NO_BARS)
        if (bars.size < 2) return ChartState.Empty(CHART_TOO_SHORT)
        val points = bars.sortedBy { it.tradeDate }.map { ChartPoint(it.tradeDate, it.closePrice) }
        return ChartState.Line(
            points = points,
            caption = "최근 ${points.size}거래일 종가",
            startDate = date(points.first().tradeDate),
            endDate = date(points.last().tradeDate),
            high = "최고 ${won(points.maxOf { it.closePrice })}",
            low = "최저 ${won(points.minOf { it.closePrice })}",
            latest = won(points.last().closePrice),
        )
    }

    fun factors(asOfDate: LocalDate, results: List<FactorCalculationResult>): FactorPanel {
        val byCode = results.associateBy { it.factorCode }
        val rows = FactorCodes.SYSTEM.mapNotNull { code -> byCode[code]?.let(::factorRow) }
        return FactorPanel.Rows(asOf = "${date(asOfDate)} 저장 일봉 기준", rows = rows)
    }

    private fun factorRow(result: FactorCalculationResult): FactorRow {
        val name = KoreanLabels.factorName(result.factorCode)
        val raw = result.rawValue
        val score = result.normalizedScore
        if (result.status != FactorCalculationStatus.SUCCESS || raw == null || score == null) {
            val message = when (result.status) {
                FactorCalculationStatus.INSUFFICIENT_HISTORY -> FACTOR_INSUFFICIENT
                FactorCalculationStatus.NO_DATA -> FACTOR_NO_DATA
                FactorCalculationStatus.INVALID_DATA, FactorCalculationStatus.SUCCESS -> FACTOR_INVALID
            }
            return FactorRow(name, result.factorCode, rawValue = null, score = null, unavailable = message)
        }
        return FactorRow(
            name = name,
            code = result.factorCode,
            rawValue = "원값 ${factorRaw(result.factorCode, raw)}",
            score = "점수 ${score.setScale(1, RoundingMode.HALF_UP).toPlainString()}",
            unavailable = null,
        )
    }

    private fun factorRaw(code: String, raw: BigDecimal): String =
        when (SystemFactorCatalog.definitions.firstOrNull { it.factorCode == code }?.valueType) {
            FactorValueType.PERCENT ->
                if (code == FactorCodes.VOLATILITY_20D) "${raw.setScale(2, RoundingMode.HALF_UP).toPlainString()}%" else signedPercent(raw)
            FactorValueType.RATIO -> "${raw.setScale(2, RoundingMode.HALF_UP).toPlainString()}배"
            else -> raw.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }

    fun themes(refs: List<ThemeRef>): List<ThemeChip> = refs.map { ThemeChip(it.themeId, it.name) }

    fun quote(quote: CurrentStockQuote, fetchedAt: Instant): QuoteCard {
        val percent = BigDecimal.valueOf(quote.changeRate)
            .multiply(HUNDRED)
            .divide(BigDecimal.valueOf(NumericMapping.RATIO_FACTOR), 2, RoundingMode.HALF_UP)
        return QuoteCard(
            price = won(quote.currentPrice),
            change = "${signedWon(quote.previousCloseDifference)} (${signedPercent(percent)})",
            direction = direction(percent),
            open = won(quote.openPrice),
            high = won(quote.highPrice),
            low = won(quote.lowPrice),
            volume = shares(quote.volume),
            businessDate = quote.businessDate?.let(::date),
            fetchedAt = "${KoreanLabels.dateTime(fetchedAt)} 조회",
        )
    }

    fun won(value: Long): String = "${String.format(Locale.US, "%,d", value)}원"

    fun signedWon(value: Long): String = if (value > 0) "+${won(value)}" else won(value)

    fun shares(value: Long): String = "${String.format(Locale.US, "%,d", value)}주"

    fun date(value: LocalDate): String = "%04d.%02d.%02d".format(Locale.US, value.year, value.monthValue, value.dayOfMonth)

    fun signedPercent(value: BigDecimal): String {
        val scaled = value.setScale(2, RoundingMode.HALF_UP)
        return if (scaled.signum() > 0) "+${scaled.toPlainString()}%" else "${scaled.toPlainString()}%"
    }

    private fun direction(percent: BigDecimal?): PriceDirection = when {
        percent == null -> PriceDirection.UNKNOWN
        percent.signum() > 0 -> PriceDirection.UP
        percent.signum() < 0 -> PriceDirection.DOWN
        else -> PriceDirection.FLAT
    }

    private fun subtitle(instrument: InstrumentSummary): String =
        listOfNotNull(instrument.symbol, boardLabel(instrument.board)).joinToString(" · ")

    private val HUNDRED = BigDecimal(100)
}
