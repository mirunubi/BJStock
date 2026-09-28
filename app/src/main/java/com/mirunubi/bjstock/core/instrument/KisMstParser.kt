package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.InstrumentType
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses KIS MST fixed-width files by **byte offset**, not UTF-8/string index.
 *
 * Official sample (koreainvestment/open-trading-api) layout after CP949 decode:
 * - last 228 (KOSPI) / 222 (KOSDAQ) characters are the tail
 * - part1[0:9] short code, [9:21] standard code, [21:] Korean name
 *
 * This parser applies the same windows on raw bytes so Korean multibyte names
 * are not sliced by UTF-16/UTF-8 code units.
 */
class KisMstParser(
    private val charset: Charset = CHARSET,
) {
    fun parse(mstBytes: ByteArray, board: Board): InstrumentMasterParseResult {
        val tailLen = InstrumentMasterConfig.tailBytes(board)
        val layout = KisMstTailLayout.forBoard(board)
        val bySymbol = LinkedHashMap<String, InstrumentMasterEntry>()
        var totalLines = 0
        var skippedUnsupportedRows = 0
        var malformedRows = 0
        var duplicateSymbols = 0

        for (line in splitLines(mstBytes)) {
            if (line.isEmpty()) continue
            totalLines += 1
            val outcome = parseLine(line, board, tailLen, layout)
            when (outcome.category) {
                MstRowCategory.SKIPPED_UNSUPPORTED -> skippedUnsupportedRows += 1
                MstRowCategory.MALFORMED -> malformedRows += 1
                MstRowCategory.TARGET_VALID -> {
                    val entry = checkNotNull(outcome.entry)
                    if (bySymbol.containsKey(entry.symbol)) {
                        duplicateSymbols += 1
                    } else {
                        bySymbol[entry.symbol] = entry
                    }
                }
            }
        }

        return InstrumentMasterParseResult(
            entries = bySymbol.values.toList(),
            stats = InstrumentMasterParseStats(
                totalLines = totalLines,
                parsedRows = bySymbol.size,
                skippedUnsupportedRows = skippedUnsupportedRows,
                malformedRows = malformedRows,
                duplicateSymbols = duplicateSymbols,
            ),
        )
    }

    internal fun classifyLine(line: ByteArray, board: Board): MstRowOutcome =
        parseLine(
            line = line,
            board = board,
            tailLen = InstrumentMasterConfig.tailBytes(board),
            layout = KisMstTailLayout.forBoard(board),
        )

    private fun parseLine(
        line: ByteArray,
        board: Board,
        tailLen: Int,
        layout: KisMstTailLayout,
    ): MstRowOutcome {
        if (line.size <= tailLen + SYMBOL_END) return MstRowOutcome.MALFORMED
        val part1End = line.size - tailLen
        if (part1End <= NAME_START) return MstRowOutcome.MALFORMED
        val symbol = decode(line, SYMBOL_START, SYMBOL_END).trim()
        val name = decode(line, NAME_START, part1End - 1).trim()
        if (!MVP_LISTED_SYMBOL.matches(symbol)) {
            val standardCode = decode(line, STANDARD_START, STANDARD_END).trim()
            val wellFormed = SOURCE_SYMBOL.matches(symbol) &&
                SOURCE_STANDARD_CODE.matches(standardCode) &&
                name.isNotEmpty()
            return if (wellFormed) MstRowOutcome.SKIPPED_UNSUPPORTED else MstRowOutcome.MALFORMED
        }
        val standardCode = decode(line, STANDARD_START, STANDARD_END).trim().ifEmpty { null }
        if (name.isEmpty()) return MstRowOutcome.MALFORMED
        val tail = line.copyOfRange(part1End, line.size)
        val spac = layout.readFlag(tail, layout.spacOffset)
        val etp = layout.readFlag(tail, layout.etpOffset)
        val preferred = layout.readFlag(tail, layout.preferredOffset)
        return MstRowOutcome(
            category = MstRowCategory.TARGET_VALID,
            entry = InstrumentMasterEntry(
                symbol = symbol,
                standardCode = standardCode,
                name = name,
                market = InstrumentMasterConfig.MARKET_KRX,
                board = board,
                instrumentType = classify(spac, etp, preferred),
                listedDate = parseListedDate(layout.readField(tail, layout.listedDateOffset, 8)),
            ),
        )
    }

    private fun decode(bytes: ByteArray, start: Int, endInclusive: Int): String {
        if (start >= bytes.size) return ""
        val endExclusive = minOf(endInclusive + 1, bytes.size)
        if (endExclusive <= start) return ""
        return String(bytes, start, endExclusive - start, charset)
    }

    private fun parseListedDate(raw: String): LocalDate? {
        val digits = raw.trim()
        if (digits.length != 8 || digits == "00000000" || digits.any { !it.isDigit() }) {
            return null
        }
        return try {
            LocalDate.parse(digits, LISTED_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun splitLines(bytes: ByteArray): List<ByteArray> {
        val lines = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == LF) {
                var end = i
                if (end > start && bytes[end - 1] == CR) end -= 1
                lines += bytes.copyOfRange(start, end)
                start = i + 1
            }
            i += 1
        }
        if (start < bytes.size) {
            var end = bytes.size
            if (end > start && bytes[end - 1] == CR) end -= 1
            lines += bytes.copyOfRange(start, end)
        }
        return lines
    }

    companion object {
        val CHARSET: Charset = Charset.forName("MS949")
        private val MVP_LISTED_SYMBOL = Regex("^\\d{6}$")

        // Short-code shapes KRX publishes in the master (ETN Q-codes, new alphanumeric codes,
        // fund/REIT F-codes, warrant J-codes, K-suffix preferred). Anything else is malformed.
        private val SOURCE_SYMBOL = Regex("^[0-9A-Z]{6,9}$")
        private val SOURCE_STANDARD_CODE = Regex("^[A-Z]{2}[0-9A-Z]{10}$")
        private val LISTED_DATE = DateTimeFormatter.BASIC_ISO_DATE
        private const val SYMBOL_START = 0
        private const val SYMBOL_END = 8
        private const val STANDARD_START = 9
        private const val STANDARD_END = 20
        private const val NAME_START = 21
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D

        internal fun classify(spac: String, etp: String, preferred: String): InstrumentType {
            if (spac == "Y") return InstrumentType.SPAC
            if (etp == "Y") return InstrumentType.ETP
            if (preferred == "Y") return InstrumentType.PREFERRED_STOCK
            val spacClear = spac.isEmpty() || spac == "N"
            val etpClear = etp.isEmpty() || etp == "N"
            val preferredClear = preferred.isEmpty() || preferred == "N"
            return if (spacClear && etpClear && preferredClear) {
                InstrumentType.COMMON_STOCK
            } else {
                InstrumentType.OTHER
            }
        }
    }
}

internal data class MstRowOutcome(
    val category: MstRowCategory,
    val entry: InstrumentMasterEntry? = null,
) {
    companion object {
        val SKIPPED_UNSUPPORTED = MstRowOutcome(MstRowCategory.SKIPPED_UNSUPPORTED)
        val MALFORMED = MstRowOutcome(MstRowCategory.MALFORMED)
    }
}

internal data class KisMstTailLayout(
    val etpOffset: Int,
    val spacOffset: Int,
    val listedDateOffset: Int,
    val preferredOffset: Int,
) {
    fun readFlag(tail: ByteArray, offset: Int): String = readField(tail, offset, 1).trim().uppercase()

    fun readField(tail: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset >= tail.size) return ""
        val end = minOf(offset + length, tail.size)
        return String(tail, offset, end - offset, KisMstParser.CHARSET)
    }

        companion object {
        fun forBoard(board: Board): KisMstTailLayout = when (board) {
            Board.KOSPI -> KOSPI
            Board.KOSDAQ -> KOSDAQ
            Board.OTHER -> error("OTHER has no MST tail layout")
        }

        // Official koreainvestment/open-trading-api kospi field_specs (character/byte widths).
        private val KOSPI_WIDTHS = intArrayOf(
            2, 1, 4, 4, 4,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 1,
            1, 9, 5, 5, 1,
            1, 1, 2, 1, 1,
            1, 2, 2, 2, 3,
            1, 3, 12, 12, 8,
            15, 21, 2, 7, 1,
            1, 1, 1, 1, 9,
            9, 9, 5, 9, 8,
            9, 3, 1, 1, 1,
        )

        // Official kosdaq field_specs.
        private val KOSDAQ_WIDTHS = intArrayOf(
            2, 1,
            4, 4, 4, 1, 1,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 1,
            1, 1, 1, 1, 9,
            5, 5, 1, 1, 1,
            2, 1, 1, 1, 2,
            2, 2, 3, 1, 3,
            12, 12, 8, 15, 21,
            2, 7, 1, 1, 1,
            1, 9, 9, 9, 5,
            9, 8, 9, 3, 1,
            1, 1,
        )

        private val KOSPI = fromWidths(
            widths = KOSPI_WIDTHS,
            etpIndex = 12,
            spacIndex = 19,
            listedDateIndex = 49,
            preferredIndex = 54,
        )
        private val KOSDAQ = fromWidths(
            widths = KOSDAQ_WIDTHS,
            etpIndex = 8,
            spacIndex = 14,
            listedDateIndex = 44,
            preferredIndex = 49,
        )

        private fun fromWidths(
            widths: IntArray,
            etpIndex: Int,
            spacIndex: Int,
            listedDateIndex: Int,
            preferredIndex: Int,
        ): KisMstTailLayout {
            val offsets = IntArray(widths.size)
            var cursor = 0
            widths.forEachIndexed { index, width ->
                offsets[index] = cursor
                cursor += width
            }
            return KisMstTailLayout(
                etpOffset = offsets[etpIndex],
                spacOffset = offsets[spacIndex],
                listedDateOffset = offsets[listedDateIndex],
                preferredOffset = offsets[preferredIndex],
            )
        }
    }
}
