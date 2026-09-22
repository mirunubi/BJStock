package com.mirunubi.bjstock.core.factor

import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import java.time.LocalDate

class FactorCalculationService(
    private val registry: FactorRegistry,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val factorValues: FactorValueRepository,
) {
    suspend fun calculateFactor(
        instrumentId: Long,
        factorCode: String,
        asOfDate: LocalDate,
        persist: Boolean = true,
    ): FactorCalculationResult {
        factorValues.ensureSystemFactorDefinitions()
        val binding = registry.require(factorCode)
        val result = compute(instrumentId, binding, asOfDate)
        if (persist && result.isSuccess) {
            persistSuccess(result)
        }
        return result
    }

    suspend fun calculateAllSystemFactors(
        instrumentId: Long,
        asOfDate: LocalDate,
        persist: Boolean = true,
    ): List<FactorCalculationResult> {
        factorValues.ensureSystemFactorDefinitions()
        val results = registry.systemBindings().map { binding ->
            compute(instrumentId, binding, asOfDate)
        }
        if (persist) {
            results.filter { it.isSuccess }.forEach { persistSuccess(it) }
        }
        return results
    }

    private suspend fun compute(
        instrumentId: Long,
        binding: FactorBinding,
        asOfDate: LocalDate,
    ): FactorCalculationResult {
        if (instrumentDao.findById(instrumentId) == null) {
            return failed(binding, instrumentId, asOfDate, FactorCalculationStatus.NO_DATA)
        }
        val newestFirst = marketDailyBarDao.findBarsUpToDate(
            instrumentId = instrumentId,
            asOfDate = asOfDate,
            limit = binding.calculator.requiredHistoryDays,
        )
        val bars = newestFirst
            .reversed()
            .map { entity ->
                FactorBar(
                    tradeDate = entity.tradeDate,
                    closePrice = entity.closePrice,
                    volume = entity.volume,
                )
            }
        val raw = binding.calculator.calculate(instrumentId, asOfDate, bars)
        if (raw.status != FactorCalculationStatus.SUCCESS || raw.rawValue == null) {
            return failed(binding, instrumentId, asOfDate, raw.status)
        }
        val score = binding.normalizer.normalize(raw.rawValue)
        return FactorCalculationResult(
            factorCode = binding.factorCode,
            instrumentId = instrumentId,
            asOfDate = asOfDate,
            status = FactorCalculationStatus.SUCCESS,
            rawValue = raw.rawValue,
            normalizedScore = score,
            calculationVersion = binding.calculationVersion,
        )
    }

    private suspend fun persistSuccess(result: FactorCalculationResult) {
        val definition = factorValues.findDefinitionByCode(result.factorCode)
            ?: error("system factor definition missing: ${result.factorCode}")
        factorValues.upsertSuccess(
            instrumentId = result.instrumentId,
            factorId = definition.id,
            evaluationDate = result.asOfDate,
            rawValue = FactorMath.formatRaw(result.rawValue!!),
            normalizedScore = FactorScoreCodec.toStored(result.normalizedScore!!),
            source = FactorSources.BJSTOCK_MARKET_ENGINE,
            calculationVersion = result.calculationVersion,
        )
    }

    private fun failed(
        binding: FactorBinding,
        instrumentId: Long,
        asOfDate: LocalDate,
        status: FactorCalculationStatus,
    ): FactorCalculationResult = FactorCalculationResult(
        factorCode = binding.factorCode,
        instrumentId = instrumentId,
        asOfDate = asOfDate,
        status = status,
        rawValue = null,
        normalizedScore = null,
        calculationVersion = binding.calculationVersion,
    )
}
