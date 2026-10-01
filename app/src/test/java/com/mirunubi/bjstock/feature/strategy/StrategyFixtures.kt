package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object StrategyFixtures {
    const val STRATEGY_ID = 1L
    val SAMSUNG = PreviewInstrument(instrumentId = 11, symbol = "005930", name = "삼성전자", board = Board.KOSPI)

    /** factor_id = index + 1 in [FactorCodes.SYSTEM] order. */
    val FACTOR_CODES: Map<Long, String> = FactorCodes.SYSTEM.mapIndexed { index, code -> (index + 1L) to code }.toMap()

    fun strategy(id: Long = STRATEGY_ID, code: String = "MOMENTUM_BASIC", name: String = "기본 모멘텀 전략") =
        StrategyEntity(id = id, strategyCode = code, strategyName = name, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    fun version(
        id: Long,
        versionNo: Int,
        status: StrategyVersionStatus,
        sell: String = "40",
        buy: String = "70",
        strategyId: Long = STRATEGY_ID,
    ) = StrategyVersionEntity(
        id = id,
        strategyId = strategyId,
        versionNo = versionNo,
        sellThreshold = score(sell),
        buyThreshold = score(buy),
        status = status,
        createdAt = Instant.EPOCH,
    )

    fun weight(
        versionId: Long,
        code: String,
        percent: String,
        enabled: Boolean = true,
        min: String? = null,
        max: String? = null,
    ) = StrategyFactorWeightEntity(
        id = 0,
        strategyVersionId = versionId,
        factorId = FACTOR_CODES.entries.single { it.value == code }.key,
        weight = StrategyScoreMath.percentToWeightStored(BigDecimal(percent)),
        minScore = min?.let(::score),
        maxScore = max?.let(::score),
        enabled = enabled,
        createdAt = Instant.EPOCH,
        factorCalculationVersion = "v1",
    )

    fun rule(
        id: Long,
        versionId: Long,
        code: String,
        operator: SignalOperator,
        threshold: String,
        action: SignalAction,
        priority: Int,
        enabled: Boolean = true,
    ) = StrategySignalRuleEntity(
        id = id,
        strategyVersionId = versionId,
        ruleCode = code,
        metricCode = SignalMetricCode.DAILY_CHANGE_PCT,
        operator = operator,
        thresholdValue = threshold,
        action = action,
        priority = priority,
        enabled = enabled,
        createdAt = Instant.EPOCH,
    )

    /** Six factors summing to 100% (10 + 10 + 25 + 25 + 20 + 10); 20일 변동성 has a minimum score of 30. */
    fun fullWeights(versionId: Long) = listOf(
        weight(versionId, FactorCodes.PRICE_VS_MA20, "10"),
        weight(versionId, FactorCodes.PRICE_VS_MA60, "10"),
        weight(versionId, FactorCodes.MOMENTUM_20D, "25"),
        weight(versionId, FactorCodes.MOMENTUM_60D, "25"),
        weight(versionId, FactorCodes.VOLATILITY_20D, "20", min = "30"),
        weight(versionId, FactorCodes.VOLUME_RATIO_20D, "10"),
    )

    fun demoRules(versionId: Long) = listOf(
        rule(101, versionId, "DIP_BUY", SignalOperator.LTE, "-5", SignalAction.BUY, 10),
        rule(102, versionId, "SPIKE_SELL", SignalOperator.GTE, "3", SignalAction.SELL, 20),
    )

    fun score(value: String): Long = StrategyScoreMath.scoreToStored(BigDecimal(value))
}

/** In-memory [StrategyDataSource]; reads are free, every write is recorded in [writes]. */
class FakeStrategyDataSource : StrategyDataSource {
    val strategies = mutableListOf(StrategyFixtures.strategy())
    val versions = mutableListOf(
        StrategyFixtures.version(id = 10, versionNo = 1, status = StrategyVersionStatus.ACTIVE),
        StrategyFixtures.version(id = 20, versionNo = 2, status = StrategyVersionStatus.ACTIVE),
        StrategyFixtures.version(id = 30, versionNo = 3, status = StrategyVersionStatus.DRAFT),
        StrategyFixtures.version(id = 40, versionNo = 4, status = StrategyVersionStatus.RETIRED),
    )
    val weights = mutableMapOf(
        10L to StrategyFixtures.fullWeights(10),
        20L to StrategyFixtures.fullWeights(20),
        30L to StrategyFixtures.fullWeights(30),
        40L to StrategyFixtures.fullWeights(40),
    )
    val rules = mutableMapOf(20L to StrategyFixtures.demoRules(20), 30L to StrategyFixtures.demoRules(30))
    val instruments = mutableListOf(StrategyFixtures.SAMSUNG)
    val tradeDates = mutableMapOf(StrategyFixtures.SAMSUNG.instrumentId to listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 26)))

    var activationResult: StrategyActivationResult = StrategyActivationResult.Success(30)
    var writeFailure: Exception? = null
    var previewResult = StrategyEvaluationResult(status = StrategyEvaluationStatus.INSUFFICIENT_FACTORS)

    val writes = mutableListOf<String>()
    val previewCalls = mutableListOf<Triple<Long, Long, LocalDate>>()
    val savedWeights = mutableListOf<DraftWeight>()

    override suspend fun strategies(): List<StrategyEntity> = strategies.toList()

    override suspend fun versions(strategyId: Long): List<StrategyVersionEntity> =
        versions.filter { it.strategyId == strategyId }.sortedBy { it.versionNo }

    override suspend fun snapshot(versionId: Long): VersionSnapshot? {
        val version = versions.firstOrNull { it.id == versionId } ?: return null
        return VersionSnapshot(version, weights[versionId].orEmpty(), StrategyFixtures.FACTOR_CODES, rules[versionId].orEmpty())
    }

    override fun calculationVersions(factorCode: String): List<String> = listOf("v1")

    override suspend fun strategyCodeExists(code: String): Boolean = strategies.any { it.strategyCode == code.trim() }

    override suspend fun createStrategy(code: String, name: String): Pair<Long, Long> {
        record("createStrategy:$code")
        val strategyId = strategies.maxOf { it.id } + 1
        strategies += StrategyFixtures.strategy(id = strategyId, code = code, name = name)
        val versionId = versions.maxOf { it.id } + 1
        versions += StrategyFixtures.version(versionId, 1, StrategyVersionStatus.DRAFT, strategyId = strategyId)
        return strategyId to versionId
    }

    override suspend fun createDraft(strategyId: Long): Long {
        record("createDraft:$strategyId")
        val id = versions.maxOf { it.id } + 1
        versions += StrategyFixtures.version(id, versions.filter { it.strategyId == strategyId }.maxOf { it.versionNo } + 1, StrategyVersionStatus.DRAFT)
        return id
    }

    override suspend fun copyToDraft(sourceVersionId: Long): Long {
        record("copyToDraft:$sourceVersionId")
        val source = versions.single { it.id == sourceVersionId }
        val id = versions.maxOf { it.id } + 1
        versions += source.copy(id = id, versionNo = versions.maxOf { it.versionNo } + 1, status = StrategyVersionStatus.DRAFT)
        weights[id] = weights[sourceVersionId].orEmpty().map { it.copy(strategyVersionId = id) }
        return id
    }

    override suspend fun saveThresholds(versionId: Long, sellStored: Long, buyStored: Long) {
        record("saveThresholds:$versionId:$sellStored:$buyStored")
        val index = versions.indexOfFirst { it.id == versionId }
        versions[index] = versions[index].copy(sellThreshold = sellStored, buyThreshold = buyStored)
    }

    override suspend fun saveWeights(versionId: Long, weights: List<DraftWeight>) {
        record("saveWeights:$versionId")
        savedWeights += weights
    }

    override suspend fun saveRule(
        versionId: Long,
        ruleCode: String,
        operator: SignalOperator,
        thresholdValue: String,
        action: SignalAction,
        priority: Int,
    ) {
        record("saveRule:$versionId:$ruleCode:$operator:$thresholdValue:$action:$priority")
    }

    override suspend fun deleteRule(ruleId: Long) {
        record("deleteRule:$ruleId")
    }

    override suspend fun activate(versionId: Long): StrategyActivationResult {
        record("activate:$versionId")
        if (activationResult is StrategyActivationResult.Success) {
            val index = versions.indexOfFirst { it.id == versionId }
            versions[index] = versions[index].copy(status = StrategyVersionStatus.ACTIVE)
        }
        return activationResult
    }

    override suspend fun searchInstruments(query: String): List<PreviewInstrument> =
        instruments.filter { query in it.symbol || query in it.name }

    override suspend fun recentTradeDates(instrumentId: Long, limit: Int): List<LocalDate> =
        tradeDates[instrumentId].orEmpty().take(limit)

    override suspend fun preview(versionId: Long, instrumentId: Long, date: LocalDate): StrategyEvaluationResult {
        previewCalls += Triple(versionId, instrumentId, date)
        return previewResult
    }

    private fun record(write: String) {
        writeFailure?.let { throw it }
        writes += write
    }
}
