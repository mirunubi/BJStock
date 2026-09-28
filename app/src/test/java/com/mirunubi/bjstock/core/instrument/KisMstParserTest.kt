package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.InstrumentType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(0, result.stats.skippedUnsupportedRows)
        assertEquals(0, result.stats.malformedRows)
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
    fun numericSixDigitStock_isTargetValid() {
        val outcome = parser.classifyLine(
            MstFixtures.kospiLine("005930", "KR7005930003", "삼성전자"),
            Board.KOSPI,
        )
        assertEquals(MstRowCategory.TARGET_VALID, outcome.category)
        assertEquals("005930", outcome.entry?.symbol)
    }

    @Test
    fun alphanumericNewCode_isSkippedUnsupported() {
        listOf(
            MstFixtures.kospiLine("0000D0", "KR70000D0009", "TIGER 테스트ETF"),
            MstFixtures.kospiLine("0001A0", "KR70001A0000", "신규상장코드"),
            MstFixtures.kospiLine("A005930", "KR7005930003", "접두어코드"),
        ).forEach { line ->
            val outcome = parser.classifyLine(line, Board.KOSPI)
            assertEquals(MstRowCategory.SKIPPED_UNSUPPORTED, outcome.category)
            assertNull(outcome.entry)
        }
    }

    @Test
    fun etnCode_isSkippedUnsupported() {
        val outcome = parser.classifyLine(
            MstFixtures.kospiLine("Q500067", "KRG500670675", "신한 레버리지 10년 국채선물 ETN"),
            Board.KOSPI,
        )
        assertEquals(MstRowCategory.SKIPPED_UNSUPPORTED, outcome.category)
    }

    @Test
    fun fundReitWarrantAndKSuffixPreferred_areSkippedUnsupported() {
        listOf(
            MstFixtures.kospiLine("F70100030", "KR5701000307", "한투한미핵심성장포커스1(A)"),
            MstFixtures.kospiLine("F70101B95", "KR570101B957", "밀라노부동산"),
            MstFixtures.kospiLine("J0036221D", "KRA003622D12", "KG모빌리티 122WR"),
            MstFixtures.kospiLine("00088K", "KR700088K015", "한화3우B"),
        ).forEach { line ->
            assertEquals(
                MstRowCategory.SKIPPED_UNSUPPORTED,
                parser.classifyLine(line, Board.KOSPI).category,
            )
        }
    }

    @Test
    fun brokenRows_areMalformed() {
        val truncated = MstFixtures.kospiLine("005930", "KR7005930003", "삼성전자")
            .copyOfRange(0, 40)
        val cases = listOf(
            truncated,
            MstFixtures.kospiLine("12345", "KR7000000001", "짧은코드"),
            MstFixtures.kospiLine("005930", "KR7005930003", ""),
            MstFixtures.kospiLine("A005930", "", "표준코드없음"),
            MstFixtures.kospiLine("A005930", "KR70059300", "표준코드짧음"),
            MstFixtures.kospiLine("ab#123", "KR7000000001", "깨진코드"),
            MstFixtures.kospiLine("0000D0", "KR70000D0009", ""),
        )
        cases.forEach { line ->
            assertEquals(MstRowCategory.MALFORMED, parser.classifyLine(line, Board.KOSPI).category)
        }
    }

    @Test
    fun parseStats_separateTargetSkippedAndMalformed() {
        val bytes = MstFixtures.join(
            MstFixtures.kospiLine("005930", "KR7005930003", "삼성전자"),
            MstFixtures.kospiLine("Q500067", "KRG500670675", "신한 ETN"),
            MstFixtures.kospiLine("0000D0", "KR70000D0009", "TIGER ETF"),
            MstFixtures.kospiLine("12345", "KR7000000001", "짧은코드"),
        )
        val result = parser.parse(bytes, Board.KOSPI)
        assertEquals(4, result.stats.totalLines)
        assertEquals(1, result.stats.parsedRows)
        assertEquals(2, result.stats.skippedUnsupportedRows)
        assertEquals(1, result.stats.malformedRows)
        assertEquals(listOf("005930"), result.entries.map { it.symbol })
    }

    @Test
    fun ms949KoreanNames_decodeAlongsideSkippedRows() {
        val bytes = MstFixtures.join(
            MstFixtures.kospiLine("F70101B95", "KR570101B957", "밀라노부동산"),
            MstFixtures.kospiLine("000660", "KR7000660001", "SK하이닉스"),
            MstFixtures.kospiLine("00088K", "KR700088K015", "한화3우B"),
            MstFixtures.kospiLine("005380", "KR7005380001", "현대차"),
        )
        val result = parser.parse(bytes, Board.KOSPI)
        assertEquals(0, result.stats.malformedRows)
        assertEquals(2, result.stats.skippedUnsupportedRows)
        assertEquals(listOf("SK하이닉스", "현대차"), result.entries.map { it.name })
    }

    @Test
    fun kosdaq_skipsAlphanumericCodesWithoutMalformedCount() {
        val bytes = MstFixtures.join(
            MstFixtures.kosdaqLine("035720", "KR7035720002", "카카오"),
            MstFixtures.kosdaqLine("0001A0", "KR70001A0000", "덕양에너젠"),
            MstFixtures.kosdaqLine("03481K", "KR703481K015", "해성산업1우"),
        )
        val result = parser.parse(bytes, Board.KOSDAQ)
        assertEquals(1, result.stats.parsedRows)
        assertEquals(2, result.stats.skippedUnsupportedRows)
        assertEquals(0, result.stats.malformedRows)
        assertEquals(Board.KOSDAQ, result.entries.single().board)
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
