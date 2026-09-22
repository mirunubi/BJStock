package com.mirunubi.bjstock.core.factor

import java.math.BigDecimal
import java.time.LocalDate

enum class FactorCalculationStatus {
    SUCCESS,
    INSUFFICIENT_HISTORY,
    NO_DATA,
    INVALID_DATA,
}

data class FactorCalculationResult(
    val factorCode: String,
    val instrumentId: Long,
    val asOfDate: LocalDate,
    val status: FactorCalculationStatus,
    val rawValue: BigDecimal? = null,
    val normalizedScore: BigDecimal? = null,
    val calculationVersion: String,
) {
    val isSuccess: Boolean = status == FactorCalculationStatus.SUCCESS
}

data class FactorBar(
    val tradeDate: LocalDate,
    val closePrice: Long,
    val volume: Long,
)

data class FactorRawOutcome(
    val status: FactorCalculationStatus,
    val rawValue: BigDecimal? = null,
)
