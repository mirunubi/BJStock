package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategySignalRuleDao
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorRegistry
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import java.math.BigDecimal
import java.time.Instant

class StrategyVersionService(
    private val strategyDao: StrategyDao,
    private val factorValues: FactorValueRepository,
    private val registry: FactorRegistry,
    private val signalRuleDao: StrategySignalRuleDao? = null,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun createStrategy(code: String, name: String, description: String? = null): Long {
        val trimmed = code.trim()
        require(trimmed.isNotEmpty()) { "strategy_code must not be blank" }
        require(name.trim().isNotEmpty()) { "strategy_name must not be blank" }
        return strategyDao.insertStrategy(
            StrategyEntity(
                strategyCode = trimmed,
                strategyName = name.trim(),
                description = description,
                createdAt = now(),
                updatedAt = now(),
            ),
        )
    }

    suspend fun createDraftVersion(
        strategyId: Long,
        description: String? = null,
        sellThresholdStored: Long = StrategyScoreMath.scoreToStored(BigDecimal("40")),
        buyThresholdStored: Long = StrategyScoreMath.scoreToStored(BigDecimal("70")),
    ): Long {
        strategyDao.findStrategyById(strategyId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "strategy $strategyId")
        val error = StrategyVersionRules.thresholdError(sellThresholdStored, buyThresholdStored)
        if (error != null) {
            throw StrategyVersionException(StrategyErrorKind.INVALID_THRESHOLDS, error)
        }
        val versionNo = strategyDao.findMaxVersionNo(strategyId) + 1
        return strategyDao.insertVersion(
            StrategyVersionEntity(
                strategyId = strategyId,
                versionNo = versionNo,
                description = description,
                buyThreshold = buyThresholdStored,
                sellThreshold = sellThresholdStored,
                status = StrategyVersionStatus.DRAFT,
                createdAt = now(),
            ),
        )
    }

    suspend fun copyDraftFrom(sourceVersionId: Long): Long {
        val source = requireVersion(sourceVersionId)
        val newId = createDraftVersion(
            strategyId = source.strategyId,
            description = source.description,
            sellThresholdStored = source.sellThreshold,
            buyThresholdStored = source.buyThreshold,
        )
        strategyDao.findWeights(source.id).forEach { weight ->
            strategyDao.insertWeight(
                weight.copy(
                    id = 0,
                    strategyVersionId = newId,
                    createdAt = now(),
                ),
            )
        }
        signalRuleDao?.findByVersion(source.id)?.forEach { rule ->
            signalRuleDao.insert(
                rule.copy(
                    id = 0,
                    strategyVersionId = newId,
                    createdAt = now(),
                ),
            )
        }
        return newId
    }

    suspend fun findStrategyVersion(strategyVersionId: Long): StrategyVersionEntity? =
        strategyDao.findVersionById(strategyVersionId)

    suspend fun findWeights(strategyVersionId: Long): List<StrategyFactorWeightEntity> =
        strategyDao.findWeights(strategyVersionId)

    suspend fun findSignalRules(strategyVersionId: Long): List<StrategySignalRuleEntity> =
        signalRuleDao?.findByVersion(strategyVersionId).orEmpty()

    suspend fun upsertDraftSignalRule(
        strategyVersionId: Long,
        ruleCode: String,
        metricCode: SignalMetricCode = SignalMetricCode.DAILY_CHANGE_PCT,
        operator: SignalOperator,
        thresholdValue: String,
        action: SignalAction,
        priority: Int,
        enabled: Boolean = true,
        description: String? = null,
        ruleVersion: String = "v1",
    ): Long {
        requireDraft(strategyVersionId)
        val dao = requireSignalRuleDao()
        val code = ruleCode.trim()
        require(code.isNotEmpty()) { "rule_code must not be blank" }
        require(priority >= 0) { "priority must be >= 0" }
        val threshold = thresholdValue.trim()
        require(threshold.isNotEmpty()) { "threshold_value must not be blank" }
        SignalRuleEngine.parseThreshold(threshold)
            ?: throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "threshold_value is not numeric: $threshold",
            )
        val existing = dao.findByVersion(strategyVersionId).find { it.ruleCode == code }
        return if (existing == null) {
            dao.insert(
                StrategySignalRuleEntity(
                    strategyVersionId = strategyVersionId,
                    ruleCode = code,
                    metricCode = metricCode,
                    operator = operator,
                    thresholdValue = threshold,
                    action = action,
                    priority = priority,
                    enabled = enabled,
                    ruleVersion = ruleVersion.trim().ifEmpty { "v1" },
                    description = description,
                    createdAt = now(),
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    metricCode = metricCode,
                    operator = operator,
                    thresholdValue = threshold,
                    action = action,
                    priority = priority,
                    enabled = enabled,
                    ruleVersion = ruleVersion.trim().ifEmpty { existing.ruleVersion },
                    description = description,
                ),
            )
            existing.id
        }
    }

    suspend fun deleteDraftSignalRule(ruleId: Long) {
        val dao = requireSignalRuleDao()
        val rule = dao.findById(ruleId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "signal rule $ruleId")
        requireDraft(rule.strategyVersionId)
        dao.deleteById(ruleId)
    }

    suspend fun findAllStrategies(): List<StrategyEntity> = strategyDao.findAllStrategies()

    suspend fun findVersionsByStrategy(strategyId: Long): List<StrategyVersionEntity> =
        strategyDao.findVersionsByStrategy(strategyId)

    suspend fun updateDraftThresholds(
        strategyVersionId: Long,
        sellThresholdStored: Long,
        buyThresholdStored: Long,
    ) {
        val version = requireDraft(strategyVersionId)
        val error = StrategyVersionRules.thresholdError(sellThresholdStored, buyThresholdStored)
        if (error != null) {
            throw StrategyVersionException(StrategyErrorKind.INVALID_THRESHOLDS, error)
        }
        strategyDao.updateVersion(
            version.copy(
                sellThreshold = sellThresholdStored,
                buyThreshold = buyThresholdStored,
            ),
        )
    }

    suspend fun updateDraftDescription(strategyVersionId: Long, description: String?) {
        val version = requireDraft(strategyVersionId)
        strategyDao.updateVersion(version.copy(description = description))
    }

    suspend fun upsertDraftWeight(
        strategyVersionId: Long,
        factorId: Long,
        weightStored: Long,
        enabled: Boolean,
        factorCalculationVersion: String,
        minScoreStored: Long? = null,
        maxScoreStored: Long? = null,
    ): Long {
        requireDraft(strategyVersionId)
        StrategyVersionRules.weightRangeError(weightStored)?.let {
            throw StrategyVersionException(StrategyErrorKind.INVALID_WEIGHT, it)
        }
        StrategyVersionRules.gateSettingError(minScoreStored, maxScoreStored)?.let {
            throw StrategyVersionException(StrategyErrorKind.INVALID_GATE, it)
        }
        val version = factorCalculationVersion.trim()
        StrategyVersionRules.calculationVersionError(version)?.let {
            throw StrategyVersionException(StrategyErrorKind.INVALID_CALCULATION_VERSION, it)
        }
        val existing = strategyDao.findWeight(strategyVersionId, factorId)
        return if (existing == null) {
            strategyDao.insertWeight(
                StrategyFactorWeightEntity(
                    strategyVersionId = strategyVersionId,
                    factorId = factorId,
                    weight = weightStored,
                    minScore = minScoreStored,
                    maxScore = maxScoreStored,
                    enabled = enabled,
                    createdAt = now(),
                    factorCalculationVersion = version,
                ),
            )
        } else {
            strategyDao.updateWeight(
                existing.copy(
                    weight = weightStored,
                    minScore = minScoreStored,
                    maxScore = maxScoreStored,
                    enabled = enabled,
                    factorCalculationVersion = version,
                ),
            )
            existing.id
        }
    }

    suspend fun activateStrategyVersion(strategyVersionId: Long): StrategyActivationResult {
        val version = strategyDao.findVersionById(strategyVersionId)
            ?: return StrategyActivationResult.Failed(
                StrategyActivationFailure.NOT_FOUND,
                "strategy version $strategyVersionId",
            )
        if (version.status != StrategyVersionStatus.DRAFT) {
            return StrategyActivationResult.Failed(
                StrategyActivationFailure.NOT_DRAFT,
                "only DRAFT versions can be activated",
            )
        }
        StrategyVersionRules.thresholdError(version.sellThreshold, version.buyThreshold)?.let {
            return StrategyActivationResult.Failed(StrategyActivationFailure.INVALID_THRESHOLDS, it)
        }
        factorValues.ensureSystemFactorDefinitions()
        val weights = strategyDao.findWeights(strategyVersionId)
        val enabled = weights.filter { it.enabled }
        if (enabled.isEmpty()) {
            return StrategyActivationResult.Failed(
                StrategyActivationFailure.NO_ENABLED_FACTOR,
                "ACTIVE strategy version needs at least one enabled factor",
            )
        }
        StrategyVersionRules.enabledWeightSumError(enabled)?.let {
            return StrategyActivationResult.Failed(StrategyActivationFailure.INVALID_WEIGHT_SUM, it)
        }
        enabled.forEach { weight ->
            StrategyVersionRules.gateSettingError(weight.minScore, weight.maxScore)?.let {
                return StrategyActivationResult.Failed(StrategyActivationFailure.INVALID_GATE, it)
            }
            val code = factorValues.findDefinitionById(weight.factorId)?.factorCode
                ?: return StrategyActivationResult.Failed(
                    StrategyActivationFailure.UNSUPPORTED_FACTOR_VERSION,
                    "unknown factor_id ${weight.factorId}",
                )
            if (!registry.isSupported(code, weight.factorCalculationVersion)) {
                return StrategyActivationResult.Failed(
                    StrategyActivationFailure.UNSUPPORTED_FACTOR_VERSION,
                    "$code:${weight.factorCalculationVersion} is not registered",
                )
            }
        }
        val rules = signalRuleDao?.findByVersion(strategyVersionId).orEmpty()
        SignalRuleEngine.conflictError(rules)?.let {
            return StrategyActivationResult.Failed(
                StrategyActivationFailure.CONFLICTING_SIGNAL_RULES,
                it,
            )
        }
        strategyDao.updateVersionStatus(strategyVersionId, StrategyVersionStatus.ACTIVE)
        return StrategyActivationResult.Success(strategyVersionId)
    }

    suspend fun retireVersion(strategyVersionId: Long) {
        val version = requireVersion(strategyVersionId)
        if (version.status != StrategyVersionStatus.ACTIVE) {
            throw StrategyVersionException(
                StrategyErrorKind.NOT_DRAFT,
                "only ACTIVE versions can be retired",
            )
        }
        strategyDao.updateVersionStatus(strategyVersionId, StrategyVersionStatus.RETIRED)
    }

    private suspend fun requireVersion(strategyVersionId: Long): StrategyVersionEntity {
        return strategyDao.findVersionById(strategyVersionId)
            ?: throw StrategyVersionException(
                StrategyErrorKind.NOT_FOUND,
                "strategy version $strategyVersionId",
            )
    }

    private suspend fun requireDraft(strategyVersionId: Long): StrategyVersionEntity {
        val version = requireVersion(strategyVersionId)
        when (version.status) {
            StrategyVersionStatus.DRAFT -> return version
            StrategyVersionStatus.ACTIVE, StrategyVersionStatus.RETIRED -> {
                throw StrategyVersionException(
                    StrategyErrorKind.IMMUTABLE,
                    "ACTIVE/RETIRED strategy versions cannot change thresholds, weights, factor versions, gates, or signal rules",
                )
            }
        }
    }

    private fun requireSignalRuleDao(): StrategySignalRuleDao =
        signalRuleDao
            ?: throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "signal rule dao unavailable",
            )
}
