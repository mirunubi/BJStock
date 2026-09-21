package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.InstrumentType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KisMstParserTest {
    private val parser = KisMstParser()

    @Test
    fun kospi_parsesSymbolStandardCodeAndKoreanName() {
        val bytes = MstFixtures.join(
            MstFixtures.kospiLine(
                symbol = "005930",
                standardCode = "KR7005930003",
                name = "삼성전자",
                listedDate = "19750611",
            ),
        )
        val result = parser.parse(bytes, Board.KOSPI)
        assertEquals(1, result.stats.totalLines)
        assertEquals(1, result.stats.parsedRows)
        assertEquals(0, result.stats.invalidRows)
        val entry = result.entries.single()
        assertEquals("005930", entry.symbol)
        assertEquals("KR7005930003", entry.standardCode)
        assertEquals("삼성전자", entry.name)
        assertEquals(InstrumentMasterConfig.MARKET_KRX, entry.market)
        assertEquals(Board.KOSPI, entry.board)
        assertEquals(InstrumentType.COMMON_STOCK, entry.instrumentType)
        assertEquals(LocalDate.of(1975, 6, 11), entry.listedDate)
    }

    @Test
    fun kosdaq_parsesKoreanNameWithoutBreakingByteOffsets() {
        val bytes = MstFixtures.join(
            MstFixtures.kosdaqLine(
                symbol = "035720",
                standardCode = "KR7035720002",
                name = "카카오",
            ),
        )
        val result = parser.parse(bytes, Board.KOSDAQ)
        val entry = result.entries.single()
        assertEquals("035720", entry.symbol)
        assertEquals("KR7035720002", entry.standardCode)
        assertEquals("카카오", entry.name)
        assertEquals(Board.KOSDAQ, entry.board)
        assertEquals(InstrumentType.COMMON_STOCK, entry.instrumentType)
    }

    @Test
    fun koreanMultibyteName_isNotSlicedAsUtf8Characters() {
        val name = "삼성전자우선주테스트"
        val bytes = MstFixtures.join(
            MstFixtures.kospiLine("005935", "KR7005931001", name),
        )
        val parsed = parser.parse(bytes, Board.KOSPI).entries.single()
        assertEquals(name, parsed.name)
        val asUtf8 = String(bytes, Charsets.UTF_8)
        assertTrue(asUtf8 != name)
    }

    @Test
    fun nonSixDigitSymbol_isInvalid() {
        val bytes = MstFixtures.join(
            MstFixtures.kospiLine("A005930", "KR7005930003", "삼성전자"),
            MstFixtures.kospiLine("12345", "KR7000000001", "짧은코드"),
        )
        val result = parser.parse(bytes, Board.KOSPI)
        assertEquals(2, result.stats.totalLines)
        assertEquals(0, result.stats.parsedRows)
        assertEquals(2, result.stats.invalidRows)
    }

    @Test
    fun duplicateSymbol_keepsFirstAndCountsDuplicate() {
        val bytes = MstFixtures.join(
            MstFixtures.kospiLine("005930", "KR7005930003", "OLD"),
            MstFixtures.kospiLine("005930", "KR7005930003", "NEW"),
        )
        val result = parser.parse(bytes, Board.KOSPI)
        assertEquals(1, result.stats.parsedRows)
        assertEquals(1, result.stats.duplicateSymbols)
        assertEquals("OLD", result.entries.single().name)
    }

    @Test
    fun spacPreferredAndEtpFlags_classifyInPriorityOrder() {
        val spac = parser.parse(
            MstFixtures.join(MstFixtures.kospiLine("123456", "KR7123450001", "스팩", spac = "Y", etp = "Y")),
            Board.KOSPI,
        ).entries.single()
        assertEquals(InstrumentType.SPAC, spac.instrumentType)

        val etp = parser.parse(
            MstFixtures.join(MstFixtures.kospiLine("069500", "KR7069500007", "ETF", etp = "Y")),
            Board.KOSPI,
        ).entries.single()
        assertEquals(InstrumentType.ETP, etp.instrumentType)

        val preferred = parser.parse(
            MstFixtures.join(MstFixtures.kospiLine("005935", "KR7005931001", "우선주", preferred = "Y")),
            Board.KOSPI,
        ).entries.single()
        assertEquals(InstrumentType.PREFERRED_STOCK, preferred.instrumentType)
    }

    @Test
    fun kosdaqEtpProductCode_otherThanY_isNotGuessed() {
        val result = parser.parse(
            MstFixtures.join(
                MstFixtures.kosdaqLine("069500", "KR7069500007", "상품", etp = "1"),
            ),
            Board.KOSDAQ,
        ).entries.single()
        assertEquals(InstrumentType.OTHER, result.instrumentType)
    }
}
