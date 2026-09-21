package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object KisMarketNumeric {
    private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE

    fun parseWon(raw: String?, field: String): Long {
        val decimal = parseRequiredDecimal(raw, field)
        return try {
            decimal.setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            mappingFailure(field)
        }
    }

    fun parseOptionalWon(raw: String?, field: String): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return parseWon(raw, field)
    }

    /**
     * KIS `prdy_ctrt` is a percent string such as `-1.25`.
     * Domain stores it as a ratio scaled by [NumericMapping.RATIO_FACTOR].
     */
    fun parsePercentAsScaledRatio(raw: String?, field: String): Long {
        val percent = parseRequiredDecimal(raw, field)
        val ratio = percent.movePointLeft(2)
        return try {
            ratio.multiply(BigDecimal.valueOf(NumericMapping.RATIO_FACTOR))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: ArithmeticException) {
            mappingFailure(field)
        }
    }

    fun parseTradeDate(raw: String?, field: String): LocalDate {
        val value = raw?.trim().orEmpty()
        if (value.length != 8) {
            mappingFailure(field)
        }
        return try {
            LocalDate.parse(value, DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            mappingFailure(field)
        }
    }

    fun parseOptionalTradeDate(raw: String?): LocalDate? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) {
            return null
        }
        return parseTradeDate(value, "date")
    }

    fun formatKisDate(date: LocalDate): String = date.format(DATE_FORMAT)

    fun formatDisplayDate(date: LocalDate): String = date.toString()

    fun formatWon(value: Long): String = "%,d".format(value)

    fun formatPercentFromScaledRatio(scaled: Long): String {
        val percent = BigDecimal.valueOf(scaled)
            .divide(BigDecimal.valueOf(NumericMapping.RATIO_FACTOR))
            .movePointRight(2)
            .stripTrailingZeros()
        return percent.toPlainString() + "%"
    }

    private fun parseRequiredDecimal(raw: String?, field: String): BigDecimal {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("N/A", ignoreCase = true)) {
            mappingFailure(field)
        }
        return try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            mappingFailure(field)
        }
    }

    private fun mappingFailure(field: String): Nothing {
        throw KisMarketException(
            kind = KisMarketErrorKind.MAPPING_FAILURE,
            publicMessage = "KIS 응답 오류",
            audit = KisMarketErrorAudit(msg1 = "unparseable field: $field"),
        )
    }
}
