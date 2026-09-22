package com.mirunubi.bjstock.core.factor

import com.mirunubi.bjstock.core.factor.calculator.MomentumCalculator
import com.mirunubi.bjstock.core.factor.calculator.PriceVsMaCalculator
import com.mirunubi.bjstock.core.factor.calculator.VolatilityCalculator
import com.mirunubi.bjstock.core.factor.calculator.VolumeRatioCalculator

object SystemFactorRegistryFactory {
    fun create(): FactorRegistry {
        val version = FactorCalculationVersions.V1
        return FactorRegistry(
            listOf(
                FactorBinding(
                    calculator = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA20, window = 20),
                    normalizer = LinearNormalizer(
                        SystemFactorCatalog.ma20Zero,
                        SystemFactorCatalog.ma20Hundred,
                    ),
                    calculationVersion = version,
                    definition = SystemFactorCatalog.definition(FactorCodes.PRICE_VS_MA20),
                ),
                FactorBinding(
                    calculator = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA60, window = 60),
                    normalizer = LinearNormalizer(
                        SystemFactorCatalog.ma60Zero,
                        SystemFactorCatalog.ma60Hundred,
                    ),
                    calculationVersion = version,
                    definition = SystemFactorCatalog.definition(FactorCodes.PRICE_VS_MA60),
                ),
                FactorBinding(
                    calculator = MomentumCalculator(FactorCodes.MOMENTUM_20D, lookbackTradingDays = 20),
                    normalizer = LinearNormalizer(
                        SystemFactorCatalog.momentum20Zero,
                        SystemFactorCatalog.momentum20Hundred,
                    ),
                    calculationVersion = version,
                    definition = SystemFactorCatalog.definition(FactorCodes.MOMENTUM_20D),
                ),
                FactorBinding(
                    calculator = MomentumCalculator(FactorCodes.MOMENTUM_60D, lookbackTradingDays = 60),
                    normalizer = LinearNormalizer(
                        SystemFactorCatalog.momentum60Zero,
                        SystemFactorCatalog.momentum60Hundred,
                    ),
                    calculationVersion = version,
                    definition = SystemFactorCatalog.definition(FactorCodes.MOMENTUM_60D),
                ),
                FactorBinding(
                    calculator = VolatilityCalculator(FactorCodes.VOLATILITY_20D, returnDays = 20),
                    normalizer = LinearNormalizer(
                        SystemFactorCatalog.volatilityZero,
                        SystemFactorCatalog.volatilityHundred,
                    ),
                    calculationVersion = version,
                    definition = SystemFactorCatalog.definition(FactorCodes.VOLATILITY_20D),
                ),
                FactorBinding(
                    calculator = VolumeRatioCalculator(FactorCodes.VOLUME_RATIO_20D, priorDays = 20),
                    normalizer = PiecewiseLinearNormalizer(SystemFactorCatalog.volumeRatioPoints),
                    calculationVersion = version,
                    definition = SystemFactorCatalog.definition(FactorCodes.VOLUME_RATIO_20D),
                ),
            ),
        )
    }
}
