package com.mirunubi.bjstock.core.factor

import com.mirunubi.bjstock.core.model.FactorCategory
import com.mirunubi.bjstock.core.model.FactorValueType
import java.math.BigDecimal

object FactorCodes {
    const val PRICE_VS_MA20 = "PRICE_VS_MA20"
    const val PRICE_VS_MA60 = "PRICE_VS_MA60"
    const val MOMENTUM_20D = "MOMENTUM_20D"
    const val MOMENTUM_60D = "MOMENTUM_60D"
    const val VOLATILITY_20D = "VOLATILITY_20D"
    const val VOLUME_RATIO_20D = "VOLUME_RATIO_20D"

    val SYSTEM = listOf(
        PRICE_VS_MA20,
        PRICE_VS_MA60,
        MOMENTUM_20D,
        MOMENTUM_60D,
        VOLATILITY_20D,
        VOLUME_RATIO_20D,
    )
}

object FactorCalculationVersions {
    const val V1 = "v1"
}

object FactorSources {
    const val BJSTOCK_MARKET_ENGINE = "BJSTOCK_MARKET_ENGINE"
}

data class SystemFactorDefinition(
    val factorCode: String,
    val factorName: String,
    val category: FactorCategory,
    val description: String,
    val valueType: FactorValueType,
    val higherIsBetter: Boolean,
)

object SystemFactorCatalog {
    val definitions: List<SystemFactorDefinition> = listOf(
        SystemFactorDefinition(
            factorCode = FactorCodes.PRICE_VS_MA20,
            factorName = "Price vs MA20",
            category = FactorCategory.TECHNICAL,
            description = "(close / MA20 - 1) × 100. 20 trading days.",
            valueType = FactorValueType.PERCENT,
            higherIsBetter = true,
        ),
        SystemFactorDefinition(
            factorCode = FactorCodes.PRICE_VS_MA60,
            factorName = "Price vs MA60",
            category = FactorCategory.TECHNICAL,
            description = "(close / MA60 - 1) × 100. 60 trading days.",
            valueType = FactorValueType.PERCENT,
            higherIsBetter = true,
        ),
        SystemFactorDefinition(
            factorCode = FactorCodes.MOMENTUM_20D,
            factorName = "Momentum 20D",
            category = FactorCategory.MOMENTUM,
            description = "(close / close20DaysAgo - 1) × 100. 21 trading observations.",
            valueType = FactorValueType.PERCENT,
            higherIsBetter = true,
        ),
        SystemFactorDefinition(
            factorCode = FactorCodes.MOMENTUM_60D,
            factorName = "Momentum 60D",
            category = FactorCategory.MOMENTUM,
            description = "(close / close60DaysAgo - 1) × 100. 61 trading observations.",
            valueType = FactorValueType.PERCENT,
            higherIsBetter = true,
        ),
        SystemFactorDefinition(
            factorCode = FactorCodes.VOLATILITY_20D,
            factorName = "Volatility 20D",
            category = FactorCategory.TECHNICAL,
            description = "Population stdev of 20 daily returns as percent. Not annualized.",
            valueType = FactorValueType.PERCENT,
            higherIsBetter = false,
        ),
        SystemFactorDefinition(
            factorCode = FactorCodes.VOLUME_RATIO_20D,
            factorName = "Volume Ratio 20D",
            category = FactorCategory.VOLUME,
            description = "Current volume / average of the previous 20 trading-day volumes.",
            valueType = FactorValueType.RATIO,
            higherIsBetter = true,
        ),
    )

    fun definition(code: String): SystemFactorDefinition =
        definitions.first { it.factorCode == code }

    val ma20Zero = BigDecimal("-20")
    val ma20Hundred = BigDecimal("20")
    val ma60Zero = BigDecimal("-30")
    val ma60Hundred = BigDecimal("30")
    val momentum20Zero = BigDecimal("-20")
    val momentum20Hundred = BigDecimal("20")
    val momentum60Zero = BigDecimal("-30")
    val momentum60Hundred = BigDecimal("30")
    val volatilityZero = BigDecimal("10")
    val volatilityHundred = BigDecimal("0")
    val volumeRatioPoints = listOf(
        BigDecimal("0.5") to BigDecimal("20"),
        BigDecimal("1.0") to BigDecimal("50"),
        BigDecimal("2.0") to BigDecimal("80"),
        BigDecimal("3.0") to BigDecimal("100"),
    )
}
