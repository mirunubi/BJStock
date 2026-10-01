package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorRegistry
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.strategy.PreviewStrategyEvaluationUseCase
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationResult
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import java.time.LocalDate
import javax.inject.Inject

data class PreviewInstrument(val instrumentId: Long, val symbol: String, val name: String, val board: Board)

/** A version's stored configuration, read without side effects. */
data class VersionSnapshot(
    val version: StrategyVersionEntity,
    val weights: List<StrategyFactorWeightEntity>,
    /** factor_id -> factor_code for the stored definitions. */
    val factorCodes: Map<Long, String>,
    val rules: List<StrategySignalRuleEntity>,
)

/** One factor row to save on a DRAFT; [weightStored] / scores use the stored integer scales. */
data class DraftWeight(
    val factorCode: String,
    val weightStored: Long,
    val enabled: Boolean,
    val calculationVersion: String,
    val minScoreStored: Long?,
    val maxScoreStored: Long?,
)

/**
 * Strategy tab access. Reads have no side effects; every write delegates to [StrategyVersionService]
 * and is only called from an explicit user action.
 */
interface StrategyDataSource {
    suspend fun strategies(): List<StrategyEntity>

    suspend fun versions(strategyId: Long): List<StrategyVersionEntity>

    suspend fun snapshot(versionId: Long): VersionSnapshot?

    /** Registered calculation versions per system factor code. */
    fun calculationVersions(factorCode: String): List<String>

    suspend fun strategyCodeExists(code: String): Boolean

    /** Creates the strategy and its first DRAFT; returns (strategyId, versionId). */
    suspend fun createStrategy(code: String, name: String): Pair<Long, Long>

    suspend fun createDraft(strategyId: Long): Long

    suspend fun copyToDraft(sourceVersionId: Long): Long

    suspend fun saveThresholds(versionId: Long, sellStored: Long, buyStored: Long)

    suspend fun saveWeights(versionId: Long, weights: List<DraftWeight>)

    suspend fun saveRule(
        versionId: Long,
        ruleCode: String,
        operator: SignalOperator,
        thresholdValue: String,
        action: SignalAction,
        priority: Int,
    )

    suspend fun deleteRule(ruleId: Long)

    suspend fun activate(versionId: Long): StrategyActivationResult

    suspend fun searchInstruments(query: String): List<PreviewInstrument>

    /** Latest stored trade dates of the instrument, newest first. */
    suspend fun recentTradeDates(instrumentId: Long, limit: Int): List<LocalDate>

    /** Non-persisting evaluation; never writes evaluations, orders, executions, or cash. */
    suspend fun preview(versionId: Long, instrumentId: Long, date: LocalDate): StrategyEvaluationResult
}

class RoomStrategyDataSource @Inject constructor(
    private val strategyService: StrategyVersionService,
    private val previewEvaluation: PreviewStrategyEvaluationUseCase,
    private val strategyDao: StrategyDao,
    private val factorValues: FactorValueRepository,
    private val registry: FactorRegistry,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
) : StrategyDataSource {
    override suspend fun strategies(): List<StrategyEntity> = strategyService.findAllStrategies()

    override suspend fun versions(strategyId: Long): List<StrategyVersionEntity> =
        strategyService.findVersionsByStrategy(strategyId)

    override suspend fun snapshot(versionId: Long): VersionSnapshot? {
        val version = strategyService.findStrategyVersion(versionId) ?: return null
        return VersionSnapshot(
            version = version,
            weights = strategyService.findWeights(versionId),
            factorCodes = factorValues.findAllDefinitions().associate { it.id to it.factorCode },
            rules = strategyService.findSignalRules(versionId),
        )
    }

    override fun calculationVersions(factorCode: String): List<String> = registry.versionsFor(factorCode)

    override suspend fun strategyCodeExists(code: String): Boolean =
        strategyDao.findStrategyByCode(code.trim()) != null

    override suspend fun createStrategy(code: String, name: String): Pair<Long, Long> {
        val strategyId = strategyService.createStrategy(code, name)
        return strategyId to strategyService.createDraftVersion(strategyId)
    }

    override suspend fun createDraft(strategyId: Long): Long = strategyService.createDraftVersion(strategyId)

    override suspend fun copyToDraft(sourceVersionId: Long): Long = strategyService.copyDraftFrom(sourceVersionId)

    override suspend fun saveThresholds(versionId: Long, sellStored: Long, buyStored: Long) =
        strategyService.updateDraftThresholds(versionId, sellThresholdStored = sellStored, buyThresholdStored = buyStored)

    /** Saving needs factor ids, so the system definitions are ensured here, on the explicit save only. */
    override suspend fun saveWeights(versionId: Long, weights: List<DraftWeight>) {
        factorValues.ensureSystemFactorDefinitions()
        weights.forEach { weight ->
            val definition = factorValues.findDefinitionByCode(weight.factorCode)
                ?: error("missing factor definition")
            strategyService.upsertDraftWeight(
                strategyVersionId = versionId,
                factorId = definition.id,
                weightStored = weight.weightStored,
                enabled = weight.enabled,
                factorCalculationVersion = weight.calculationVersion,
                minScoreStored = weight.minScoreStored,
                maxScoreStored = weight.maxScoreStored,
            )
        }
    }

    override suspend fun saveRule(
        versionId: Long,
        ruleCode: String,
        operator: SignalOperator,
        thresholdValue: String,
        action: SignalAction,
        priority: Int,
    ) {
        strategyService.upsertDraftSignalRule(
            strategyVersionId = versionId,
            ruleCode = ruleCode,
            metricCode = SignalMetricCode.DAILY_CHANGE_PCT,
            operator = operator,
            thresholdValue = thresholdValue,
            action = action,
            priority = priority,
        )
    }

    override suspend fun deleteRule(ruleId: Long) = strategyService.deleteDraftSignalRule(ruleId)

    override suspend fun activate(versionId: Long): StrategyActivationResult =
        strategyService.activateStrategyVersion(versionId)

    override suspend fun searchInstruments(query: String): List<PreviewInstrument> =
        instrumentDao.searchActive("%${query.trim()}%", SEARCH_LIMIT).map(::instrument)

    override suspend fun recentTradeDates(instrumentId: Long, limit: Int): List<LocalDate> {
        val latest = marketDailyBarDao.findLatest(instrumentId) ?: return emptyList()
        return marketDailyBarDao.findBarsUpToDate(instrumentId, latest.tradeDate, limit).map { it.tradeDate }
    }

    override suspend fun preview(versionId: Long, instrumentId: Long, date: LocalDate): StrategyEvaluationResult =
        previewEvaluation(versionId, instrumentId, date)

    private fun instrument(entity: InstrumentEntity) =
        PreviewInstrument(entity.id, entity.symbol, entity.name, entity.board)

    private companion object {
        const val SEARCH_LIMIT = 20
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class StrategyScreenModule {
    @Binds
    abstract fun bindStrategyDataSource(source: RoomStrategyDataSource): StrategyDataSource
}
