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
    val lookupKey: String = lookupKey(factorCode, calculationVersion)

    companion object {
        fun lookupKey(factorCode: String, calculationVersion: String): String =
            "$factorCode:$calculationVersion"
    }
}

class FactorRegistry(
    bindings: List<FactorBinding>,
) {
    private val byCodeAndVersion = bindings.associateBy { it.lookupKey }
    private val byCode = bindings.groupBy { it.factorCode }

    fun get(factorCode: String, calculationVersion: String): FactorBinding? =
        byCodeAndVersion[FactorBinding.lookupKey(factorCode, calculationVersion)]

    fun get(factorCode: String): FactorBinding? =
        get(factorCode, FactorCalculationVersions.V1) ?: byCode[factorCode]?.firstOrNull()

    fun require(factorCode: String, calculationVersion: String): FactorBinding =
        get(factorCode, calculationVersion)
            ?: error("Unknown factor: $factorCode:$calculationVersion")

    fun require(factorCode: String): FactorBinding =
        get(factorCode) ?: error("Unknown factor code: $factorCode")

    fun isSupported(factorCode: String, calculationVersion: String): Boolean =
        get(factorCode, calculationVersion) != null

    fun versionsFor(factorCode: String): List<String> =
        byCode[factorCode].orEmpty().map { it.calculationVersion }.distinct()

    fun systemBindings(): List<FactorBinding> =
        FactorCodes.SYSTEM.map { require(it, FactorCalculationVersions.V1) }
}
