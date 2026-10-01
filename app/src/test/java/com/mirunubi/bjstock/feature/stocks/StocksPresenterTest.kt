package com.mirunubi.bjstock.feature.stocks

import com.mirunubi.bjstock.core.factor.FactorCalculationResult
import com.mirunubi.bjstock.core.factor.FactorCalculationStatus
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.model.Board
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StocksPresenterTest {
    @Test
    fun row_showsNameAndCodeWithBoardBadge_withoutInternalIds() {
        val kospi = StocksPresenter.row(StocksFixtures.SAMSUNG)
        assertEquals("삼성전자", kospi.name)
        assertEquals("005930 · KOSPI", kospi.subtitle)
        assertEquals("247540 · KOSDAQ", StocksPresenter.row(StocksFixtures.ECOPRO_BM).subtitle)
        assertEquals("900001", StocksPresenter.row(StocksFixtures.SAMSUNG.copy(symbol = "900001", board = Board.OTHER)).subtitle)
        assertFalse("${kospi.name} ${kospi.subtitle}".contains(StocksFixtures.SAMSUNG.instrumentId.toString()))
    }

    @Test
    fun header_showsOnlyMetadataThatExists() {
        assertEquals(emptyList<String>(), StocksPresenter.header(StocksFixtures.SAMSUNG).metadata)
        val withSector = StocksFixtures.SAMSUNG.copy(sector = "전기전자", industry = " ")
        assertEquals(listOf("업종 전기전자"), StocksPresenter.header(withSector).metadata)
    }

    @Test
    fun price_usesTheLatestStoredBar_andFormatsInKorean() {
        val price = StocksPresenter.price(
            listOf(
                StoredBar(LocalDate.of(2026, 9, 26), 266_000, 12_000_000),
                StoredBar(LocalDate.of(2026, 9, 29), 275_000, 15_963_864),
            ),
        )!!
        assertEquals("275,000원", price.close)
        assertEquals("2026.09.29", price.tradeDate)
        assertEquals("15,963,864주", price.volume)
        assertEquals("+9,000원 (+3.38%)", price.change)
        assertEquals(PriceDirection.UP, price.direction)
    }

    @Test
    fun dailyChange_isLatestCloseVsPreviousStoredClose() {
        assertEquals(BigDecimal("3.38"), StocksPresenter.dailyChangePercent(275_000, 266_000))
        assertEquals(BigDecimal("-1.20"), StocksPresenter.dailyChangePercent(98_800, 100_000))
        assertEquals(BigDecimal("0.00"), StocksPresenter.dailyChangePercent(100_000, 100_000))
        assertNull(StocksPresenter.dailyChangePercent(100_000, 0))

        val down = StocksPresenter.price(
            listOf(StoredBar(LocalDate.of(2026, 9, 28), 100_000, 1), StoredBar(LocalDate.of(2026, 9, 29), 98_800, 1)),
        )!!
        assertEquals("-1,200원 (-1.20%)", down.change)
        assertEquals(PriceDirection.DOWN, down.direction)
    }

    @Test
    fun noPreviousBar_changeIsUnavailable_notZero() {
        val price = StocksPresenter.price(listOf(StoredBar(LocalDate.of(2026, 9, 29), 275_000, 10)))!!
        assertEquals("—", price.change)
        assertEquals(PriceDirection.UNKNOWN, price.direction)
        assertEquals("275,000원", price.close)
    }

    @Test
    fun noBars_givesCleanEmptyStates() {
        assertNull(StocksPresenter.price(emptyList()))
        assertEquals(ChartState.Empty("저장된 시세 데이터가 없습니다."), StocksPresenter.chart(emptyList()))
        assertTrue(StocksPresenter.chart(listOf(StoredBar(LocalDate.of(2026, 9, 29), 1, 1))) is ChartState.Empty)
    }

    @Test
    fun chartPoints_areChronological_withRealDatesOnly() {
        val bars = StocksFixtures.bars(LocalDate.of(2026, 8, 1), List(25) { 100_000L + it * 100 })
        val chart = StocksPresenter.chart(bars.shuffled()) as ChartState.Line

        assertEquals(bars.map { it.tradeDate }, chart.points.map { it.tradeDate })
        assertEquals(bars.map { it.closePrice }, chart.points.map { it.closePrice })
        assertEquals("최근 25거래일 종가", chart.caption)
        assertEquals(StocksPresenter.date(bars.first().tradeDate), chart.startDate)
        assertEquals(StocksPresenter.date(bars.last().tradeDate), chart.endDate)
        assertEquals("102,400원", chart.latest)
        assertEquals("최고 102,400원", chart.high)
        assertEquals("최저 100,000원", chart.low)
    }

    @Test
    fun factorRows_areKorean_inSystemOrder_withRawAndScore() {
        val asOf = LocalDate.of(2026, 9, 29)
        val results = FactorCodes.SYSTEM.reversed().map { code ->
            StocksFixtures.factor(code, asOf, raw = if (code == FactorCodes.VOLUME_RATIO_20D) "1.35" else "4.8249", score = "61.25")
        }
        val panel = StocksPresenter.factors(asOf, results) as FactorPanel.Rows

        assertEquals("2026.09.29 저장 일봉 기준", panel.asOf)
        assertEquals(
            listOf("20일 이동평균 대비", "60일 이동평균 대비", "20일 모멘텀", "60일 모멘텀", "20일 변동성", "20일 거래량 비율"),
            panel.rows.map { it.name },
        )
        assertEquals(FactorCodes.SYSTEM, panel.rows.map { it.code })
        val momentum = panel.rows.single { it.code == FactorCodes.MOMENTUM_20D }
        assertEquals("원값 +4.82%", momentum.rawValue)
        assertEquals("점수 61.3", momentum.score)
        assertEquals("원값 4.82%", panel.rows.single { it.code == FactorCodes.VOLATILITY_20D }.rawValue)
        assertEquals("원값 1.35배", panel.rows.single { it.code == FactorCodes.VOLUME_RATIO_20D }.rawValue)
    }

    @Test
    fun insufficientHistory_isShownAsAMessage_neverAsZero() {
        val asOf = LocalDate.of(2026, 9, 29)
        val panel = StocksPresenter.factors(
            asOf,
            listOf(
                FactorCalculationResult(FactorCodes.MOMENTUM_60D, 1, asOf, FactorCalculationStatus.INSUFFICIENT_HISTORY, calculationVersion = "v1"),
                FactorCalculationResult(FactorCodes.PRICE_VS_MA20, 1, asOf, FactorCalculationStatus.INVALID_DATA, calculationVersion = "v1"),
            ),
        ) as FactorPanel.Rows

        val momentum = panel.rows.single { it.code == FactorCodes.MOMENTUM_60D }
        assertEquals("계산에 필요한 과거 데이터가 부족합니다.", momentum.unavailable)
        assertNull(momentum.rawValue)
        assertNull(momentum.score)
        assertEquals(StocksPresenter.FACTOR_INVALID, panel.rows.single { it.code == FactorCodes.PRICE_VS_MA20 }.unavailable)
        panel.rows.forEach { row -> assertFalse(row.toString().contains("점수 0")) }
    }

    @Test
    fun factorText_neverTurnsScoresIntoRecommendations() {
        val asOf = LocalDate.of(2026, 9, 29)
        val panel = StocksPresenter.factors(asOf, FactorCodes.SYSTEM.map { StocksFixtures.factor(it, asOf, "1", "95") })
        listOf("좋음", "나쁨", "매수", "매도", "추천", "위험").forEach { word -> assertFalse(panel.toString().contains(word)) }
    }

    @Test
    fun quote_isLabelledAsALookupResult_withSignedChange() {
        val card = StocksPresenter.quote(
            CurrentStockQuote(
                symbol = "005930",
                currentPrice = 276_000,
                previousCloseDifference = 1_000,
                changeRate = 363_636,
                openPrice = 275_500,
                highPrice = 277_000,
                lowPrice = 274_000,
                volume = 3_210_000,
                tradingValue = null,
                businessDate = LocalDate.of(2026, 10, 1),
                source = "KIS",
            ),
            Instant.parse("2026-10-01T03:12:00Z"),
        )
        assertEquals("276,000원", card.price)
        assertEquals("+1,000원 (+0.36%)", card.change)
        assertEquals(PriceDirection.UP, card.direction)
        assertEquals("3,210,000주", card.volume)
        assertEquals("2026.10.01", card.businessDate)
        assertEquals("10월 1일 오후 12:12 조회", card.fetchedAt)
        assertFalse(card.toString().contains("실시간"))
    }

    @Test
    fun formatting_isKoreanFriendly() {
        assertEquals("275,000원", StocksPresenter.won(275_000))
        assertEquals("-1,000원", StocksPresenter.signedWon(-1_000))
        assertEquals("0원", StocksPresenter.signedWon(0))
        assertEquals("15,963,864주", StocksPresenter.shares(15_963_864))
        assertEquals("2026.09.29", StocksPresenter.date(LocalDate.of(2026, 9, 29)))
        assertEquals("+3.38%", StocksPresenter.signedPercent(BigDecimal("3.375")))
        assertEquals("-1.20%", StocksPresenter.signedPercent(BigDecimal("-1.2")))
    }
}
