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
    fun spacEtpAndPreferredCodes_classifyInPriorityOrder() {
        fun typeOf(line: ByteArray) = parser.parse(MstFixtures.join(line), Board.KOSPI).entries.single().instrumentType

        assertEquals(
            InstrumentType.SPAC,
            typeOf(MstFixtures.kospiLine("123456", "KR7123450001", "스팩", spac = "Y", etp = "2")),
        )
        assertEquals(
            InstrumentType.ETP,
            typeOf(MstFixtures.kospiLine("069500", "KR7069500007", "ETF", groupCode = "EF", etp = "2")),
        )
        assertEquals(
            InstrumentType.ETP,
            typeOf(MstFixtures.kospiLine("069501", "KR7069501007", "ETF1", groupCode = "EF", etp = "1")),
        )
        assertEquals(
            InstrumentType.PREFERRED_STOCK,
            typeOf(MstFixtures.kospiLine("005935", "KR7005931001", "구형우선주", preferred = "1")),
        )
        assertEquals(
            InstrumentType.PREFERRED_STOCK,
            typeOf(MstFixtures.kospiLine("000087", "KR7000082008", "신형우선주", preferred = "2")),
        )
        assertEquals(
            InstrumentType.COMMON_STOCK,
            typeOf(MstFixtures.kospiLine("005930", "KR7005930003", "보통주", preferred = "0")),
        )
    }

    @Test
    fun nonStockGroups_areOther_evenWithCommonCodes() {
        listOf("RT", "IF", "MF", "DR", "FS", "EF", "").forEach { group ->
            val type = parser.parse(
                MstFixtures.join(MstFixtures.kospiLine("088260", "KR7088260005", "비주식", groupCode = group)),
                Board.KOSPI,
            ).entries.single().instrumentType
            assertEquals("group '$group'", InstrumentType.OTHER, type)
        }
    }

    @Test
    fun legacyYesNoAndUnknownCodes_areNotGuessed() {
        fun typeOf(line: ByteArray) = parser.parse(MstFixtures.join(line), Board.KOSDAQ).entries.single().instrumentType

        assertEquals(InstrumentType.OTHER, typeOf(MstFixtures.kosdaqLine("111111", "KR7111111001", "A", preferred = "Y")))
        assertEquals(InstrumentType.OTHER, typeOf(MstFixtures.kosdaqLine("222222", "KR7222222001", "B", preferred = "9")))
        assertEquals(InstrumentType.COMMON_STOCK, typeOf(MstFixtures.kosdaqLine("333333", "KR7333333001", "C", etp = "Y")))
        assertEquals(InstrumentType.COMMON_STOCK, typeOf(MstFixtures.kosdaqLine("444444", "KR7444444001", "D", etp = "0")))
    }

    @Test
    fun tailBytes_equalOfficialFieldWidthSums() {
        assertEquals(227, InstrumentMasterConfig.KOSPI_TAIL_BYTES)
        assertEquals(221, InstrumentMasterConfig.KOSDAQ_TAIL_BYTES)
        assertEquals(InstrumentMasterConfig.KOSPI_TAIL_BYTES, KisMstTailLayout.forBoard(Board.KOSPI).tailLength)
        assertEquals(InstrumentMasterConfig.KOSDAQ_TAIL_BYTES, KisMstTailLayout.forBoard(Board.KOSDAQ).tailLength)
    }

    @Test
    fun realKospiLines_parseTypeAndListedDateAtCorrectOffsets() {
        val expected = listOf(
            Triple(RealMstLines.KOSPI_005930, "삼성전자", InstrumentType.COMMON_STOCK) to LocalDate.of(1975, 6, 11),
            Triple(RealMstLines.KOSPI_000660, "SK하이닉스", InstrumentType.COMMON_STOCK) to LocalDate.of(1996, 12, 26),
            Triple(RealMstLines.KOSPI_005935, "삼성전자우", InstrumentType.PREFERRED_STOCK) to LocalDate.of(1989, 9, 25),
            Triple(RealMstLines.KOSPI_000087, "하이트진로2우B", InstrumentType.PREFERRED_STOCK) to LocalDate.of(2011, 9, 26),
            Triple(RealMstLines.KOSPI_069500, "KODEX 200", InstrumentType.ETP) to LocalDate.of(2002, 10, 14),
            Triple(RealMstLines.KOSPI_088260_REIT, "이리츠코크렙", InstrumentType.OTHER) to LocalDate.of(2018, 6, 27),
        )
        expected.forEach { (sample, listed) ->
            val (base64, name, type) = sample
            val bytes = RealMstLines.decode(base64)
            assertEquals(288, bytes.size)
            val outcome = parser.classifyLine(bytes, Board.KOSPI)
            assertEquals(name, MstRowCategory.TARGET_VALID, outcome.category)
            val entry = outcome.entry!!
            assertEquals(name, entry.name)
            assertEquals(name, type, entry.instrumentType)
            assertEquals(name, listed, entry.listedDate)
            assertEquals(Board.KOSPI, entry.board)
            assertEquals(InstrumentMasterConfig.MARKET_KRX, entry.market)
        }
    }

    @Test
    fun realKospiLongName_keepsFinalByteOfFortyByteNameField() {
        val bytes = RealMstLines.decode(RealMstLines.KOSPI_276970_LONG_NAME)
        val entry = parser.classifyLine(bytes, Board.KOSPI).entry!!
        assertEquals("276970", entry.symbol)
        assertEquals("KODEX 미국S&P500배당귀족커버드콜(합성 H)", entry.name)
        assertEquals(40, entry.name.toByteArray(KisMstParser.CHARSET).size)
        assertEquals(InstrumentType.ETP, entry.instrumentType)
        assertEquals(LocalDate.of(2017, 8, 10), entry.listedDate)
    }

    @Test
    fun realKosdaqLines_parseTypeAndListedDateAtCorrectOffsets() {
        val expected = listOf(
            Triple(RealMstLines.KOSDAQ_247540, "에코프로비엠", InstrumentType.COMMON_STOCK) to LocalDate.of(2019, 3, 5),
            Triple(RealMstLines.KOSDAQ_466690_SPAC, "키움히어로제1호스팩", InstrumentType.SPAC) to LocalDate.of(2025, 12, 12),
            Triple(RealMstLines.KOSDAQ_900110_FOREIGN, "딥커머스", InstrumentType.OTHER) to LocalDate.of(2010, 4, 23),
        )
        expected.forEach { (sample, listed) ->
            val (base64, name, type) = sample
            val bytes = RealMstLines.decode(base64)
            assertEquals(282, bytes.size)
            val entry = parser.classifyLine(bytes, Board.KOSDAQ).entry!!
            assertEquals(name, entry.name)
            assertEquals(name, type, entry.instrumentType)
            assertEquals(name, listed, entry.listedDate)
            assertEquals(Board.KOSDAQ, entry.board)
        }
    }

    @Test
    fun realLines_parseAsFileWithSkippedAndMalformedCounts() {
        val file = MstFixtures.join(
            RealMstLines.decode(RealMstLines.KOSPI_005930),
            RealMstLines.decode(RealMstLines.KOSPI_069500),
            RealMstLines.decode(RealMstLines.KOSPI_276970_LONG_NAME),
            MstFixtures.kospiLine("Q500067", "KRG500670675", "신한 ETN"),
        )
        val result = parser.parse(file, Board.KOSPI)
        assertEquals(4, result.stats.totalLines)
        assertEquals(3, result.stats.parsedRows)
        assertEquals(1, result.stats.skippedUnsupportedRows)
        assertEquals(0, result.stats.malformedRows)
    }
}
