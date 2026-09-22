package com.mirunubi.bjstock.core.factor

import java.math.BigDecimal
import java.time.LocalDate

interface FactorCalculator {
    val factorCode: String
    val requiredHistoryDays: Int

    fun calculate(
        instrumentId: Long,
        asOfDate: LocalDate,
        bars: List<FactorBar>,
    ): FactorRawOutcome
}

interface FactorNormalizer {
    fun normalize(rawValue: BigDecimal): BigDecimal
}

data class FactorBinding(
    val calculator: FactorCalculator,
    val normalizer: FactorNormalizer,
    val calculationVersion: String,
    val definition: SystemFactorDefinition,
) {
    val factorCode: String = calculator.factorCode
}

class FactorRegistry(
    bindings: List<FactorBinding>,
) {
    private val byCode = bindings.associateBy { it.factorCode }

    fun get(factorCode: String): FactorBinding? = byCode[factorCode]

    fun require(factorCode: String): FactorBinding =
        get(factorCode) ?: error("Unknown factor code: $factorCode")

    fun systemBindings(): List<FactorBinding> = FactorCodes.SYSTEM.map(::require)
}
