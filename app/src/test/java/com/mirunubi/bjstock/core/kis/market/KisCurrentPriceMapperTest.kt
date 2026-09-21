package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.network.kis.KisCurrentPriceOutputDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class KisCurrentPriceMapperTest {
    @Test
    fun mapsRequiredQuoteFields() {
        val quote = KisCurrentPriceMapper.map(
            "005930",
            KisCurrentPriceOutputDto(
                currentPrice = "72300",
                previousCloseDifference = "-900",
                changeRate = "-1.25",
                openPrice = "72100",
                highPrice = "73000",
                lowPrice = "71800",
                volume = "12345678",
                tradingValue = "890000000000",
                businessDate = "20260918",
            ),
        )
        assertEquals("005930", quote.symbol)
        assertEquals(72_300L, quote.currentPrice)
        assertEquals(72_100L, quote.openPrice)
        assertEquals(73_000L, quote.highPrice)
        assertEquals(71_800L, quote.lowPrice)
        assertEquals(12_345_678L, quote.volume)
        assertEquals(-1_250_000L, quote.changeRate)
        assertEquals(890_000_000_000L, quote.tradingValue)
        assertEquals(LocalDate.of(2026, 9, 18), quote.businessDate)
        assertEquals("KIS", quote.source)
    }
}
