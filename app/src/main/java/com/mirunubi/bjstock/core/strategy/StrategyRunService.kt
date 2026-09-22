package com.mirunubi.bjstock.core.strategy

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunInstrumentDao
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunInstrumentEntity
import com.mirunubi.bjstock.core.factor.FactorRegistry
import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.forward.ForwardTestConfig
import com.mirunubi.bjstock.core.forward.KisForwardMarketDataGateway
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.MissingTradingPolicyException
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import java.time.Instant
import java.time.LocalDate

class StrategyRunService(
    private val database: BJStockDatabase,
    private val strategyDao: StrategyDao,
    private val strategyRunDao: StrategyRunDao,
    private val universeDao: StrategyRunInstrumentDao,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val cashLedger: CashLedgerService,
    private val policyService: PaperTradingPolicyService,
    private val factorRegistry: FactorRegistry,
    private val credentials: KisCredentialStore? = null,
    private val settings: KisSettingsStore? = null,
    private val historyGateway: KisForwardMarketDataGateway? = null,
    private val themeService: com.mirunubi.bjstock.core.theme.ThemeService? = null,
    private val defaultPolicyTemplate: () -> PaperTradingPolicy = { PaperTradingPolicy.DEFAULT },
    private val now: () -> Instant = { Instant.now() },
) {
    /**
     * Lab helper: creates a READY run with policy + initial cash.
     * Forward-test universe is optional for lab paths; use [createDraftRun]/[addInstrument]/[markReady]
     * for orchestration-ready runs.
     */
    suspend fun createReadyRun(
        strategyVersionId: Long,
        runName: String,
        startDate: LocalDate,
        initialCashWon: Long,
        policyTemplate: PaperTradingPolicy = defaultPolicyTemplate(),
        endDate: LocalDate? = null,
        instrumentIds: List<Long> = emptyList(),
    ): Long {
        require(initialCashWon > 0L) { "initial_cash must be > 0" }
        val version = strategyDao.findVersionById(strategyVersionId)
            ?: throw StrategyVersionException(
                StrategyErrorKind.NOT_FOUND,
                "strategy version $strategyVersionId",
            )
        if (version.status != StrategyVersionStatus.ACTIVE) {
            throw StrategyVersionException(
                StrategyErrorKind.VERSION_NOT_ACTIVE,
                "new strategy runs require an ACTIVE strategy version",
            )
        }
        return database.withTransaction {
            val runId = strategyRunDao.insert(
                StrategyRunEntity(
                    runName = runName,
                    strategyVersionId = strategyVersionId,
                    runType = RunType.PAPER,
                    startDate = startDate,
                    endDate = endDate,
                    initialCash = initialCashWon,
                    status = RunStatus.READY,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            policyService.createSnapshot(
                strategyRunId = runId,
                template = policyTemplate,
            )
            cashLedger.appendInitialDeposit(
                strategyRunId = runId,
                amountWon = initialCashWon,
                eventDate = startDate,
            )
            for (instrumentId in instrumentIds.sorted()) {
                insertUniverseLocked(runId, instrumentId)
            }
            runId
        }
    }

    suspend fun createDraftRun(
        strategyVersionId: Long,
        runName: String,
        startDate: LocalDate,
        initialCashWon: Long,
        policyTemplate: PaperTradingPolicy = defaultPolicyTemplate(),
        endDate: LocalDate? = null,
    ): Long {
        require(initialCashWon > 0L) { "initial_cash must be > 0" }
        val version = strategyDao.findVersionById(strategyVersionId)
            ?: throw StrategyVersionException(
                StrategyErrorKind.NOT_FOUND,
                "strategy version $strategyVersionId",
            )
        if (version.status != StrategyVersionStatus.ACTIVE) {
            throw StrategyVersionException(
                StrategyErrorKind.VERSION_NOT_ACTIVE,
                "draft strategy runs require an ACTIVE strategy version",
            )
        }
        return database.withTransaction {
            val runId = strategyRunDao.insert(
                StrategyRunEntity(
                    runName = runName,
                    strategyVersionId = strategyVersionId,
                    runType = RunType.PAPER,
                    startDate = startDate,
                    endDate = endDate,
                    initialCash = initialCashWon,
                    status = RunStatus.DRAFT,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            policyService.createSnapshot(
                strategyRunId = runId,
                template = policyTemplate,
            )
            cashLedger.appendInitialDeposit(
                strategyRunId = runId,
                amountWon = initialCashWon,
                eventDate = startDate,
            )
            runId
        }
    }

    suspend fun addInstrument(strategyRunId: Long, instrumentId: Long) {
        val run = requireDraft(strategyRunId)
        instrumentDao.findById(instrumentId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "instrument $instrumentId")
        if (universeDao.exists(run.id, instrumentId)) return
        universeDao.insert(
            StrategyRunInstrumentEntity(
                strategyRunId = run.id,
                instrumentId = instrumentId,
                createdAt = now(),
            ),
        )
    }

    suspend fun addThemeToUniverse(strategyRunId: Long, themeId: Long): Int {
        requireDraft(strategyRunId)
        val themes = themeService
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "theme $themeId")
        val theme = themes.findById(themeId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "theme $themeId")
        if (!theme.isActive) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "theme is inactive",
            )
        }
        val ids = themes.listInstrumentIds(themeId)
        var added = 0
        for (instrumentId in ids) {
            if (!universeDao.exists(strategyRunId, instrumentId)) {
                universeDao.insert(
                    StrategyRunInstrumentEntity(
                        strategyRunId = strategyRunId,
                        instrumentId = instrumentId,
                        createdAt = now(),
                    ),
                )
                added++
            }
        }
        return added
    }

    suspend fun removeInstrument(strategyRunId: Long, instrumentId: Long) {
        requireDraft(strategyRunId)
        universeDao.delete(strategyRunId, instrumentId)
    }

    suspend fun listUniverse(strategyRunId: Long) = universeDao.findByRun(strategyRunId)

    suspend fun prepareHistory(strategyRunId: Long) {
        val run = strategyRunDao.findById(strategyRunId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "run $strategyRunId")
        val gateway = historyGateway
            ?: throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "history gateway unavailable",
            )
        val ids = universeDao.findByRun(strategyRunId).map { it.instrumentId }
        if (ids.isEmpty()) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                ForwardErrorCode.EMPTY_UNIVERSE.name,
            )
        }
        val outcome = gateway.prepareHistory(ids, run.startDate)
        if (!outcome.success) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                outcome.errorMessage ?: "history prepare failed",
            )
        }
    }

    suspend fun markReady(strategyRunId: Long) {
        val run = strategyRunDao.findById(strategyRunId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "run $strategyRunId")
        if (run.status != RunStatus.DRAFT) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "only DRAFT runs can become READY",
            )
        }
        val version = strategyDao.findVersionById(run.strategyVersionId)
            ?: throw StrategyVersionException(
                StrategyErrorKind.NOT_FOUND,
                "strategy version ${run.strategyVersionId}",
            )
        if (version.status != StrategyVersionStatus.ACTIVE) {
            throw StrategyVersionException(
                StrategyErrorKind.VERSION_NOT_ACTIVE,
                "strategy version must be ACTIVE",
            )
        }
        try {
            policyService.requireByRun(strategyRunId)
        } catch (_: MissingTradingPolicyException) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                ForwardErrorCode.MISSING_TRADING_POLICY.name,
            )
        }
        if (run.initialCash <= 0L) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "initial_cash must be > 0",
            )
        }
        if (universeDao.countByRun(strategyRunId) <= 0) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                ForwardErrorCode.EMPTY_UNIVERSE.name,
            )
        }
        if (credentials != null && settings != null) {
            val env = settings.selectedEnvironment()
            if (!credentials.hasCredentials(env)) {
                throw StrategyVersionException(
                    StrategyErrorKind.INVALID_STATE,
                    ForwardErrorCode.AUTH_REQUIRED.name,
                )
            }
        }
        val weights = strategyDao.findWeights(run.strategyVersionId).filter { it.enabled }
        for (weight in weights) {
            val def = database.factorDao().findDefinitionById(weight.factorId)
                ?: throw StrategyVersionException(
                    StrategyErrorKind.INVALID_STATE,
                    "factor definition missing for weight ${weight.id}",
                )
            if (!factorRegistry.isSupported(def.factorCode, weight.factorCalculationVersion)) {
                throw StrategyVersionException(
                    StrategyErrorKind.INVALID_STATE,
                    "unsupported factor ${def.factorCode}",
                )
            }
        }
        val required = maxRequiredHistory(weights.mapNotNull { weight ->
            database.factorDao().findDefinitionById(weight.factorId)?.factorCode
        })
        for (row in universeDao.findByRun(strategyRunId)) {
            val available = marketDailyBarDao.countBarsBefore(
                instrumentId = row.instrumentId,
                beforeDate = run.startDate.plusDays(1),
            )
            if (available < required) {
                throw StrategyVersionException(
                    StrategyErrorKind.INVALID_STATE,
                    ForwardErrorCode.INSUFFICIENT_WARMUP_DATA.name,
                )
            }
        }
        strategyRunDao.updateStatus(strategyRunId, RunStatus.READY, now())
    }

    private fun maxRequiredHistory(factorCodes: List<String>): Int {
        val fromFactors = factorCodes.mapNotNull { code ->
            runCatching { factorRegistry.require(code).calculator.requiredHistoryDays }.getOrNull()
        }
        return (fromFactors.maxOrNull() ?: ForwardTestConfig.WARMUP_TRADING_BARS)
            .coerceAtLeast(1)
    }

    private suspend fun requireDraft(strategyRunId: Long): StrategyRunEntity {
        val run = strategyRunDao.findById(strategyRunId)
            ?: throw StrategyVersionException(StrategyErrorKind.NOT_FOUND, "run $strategyRunId")
        if (run.status != RunStatus.DRAFT) {
            throw StrategyVersionException(
                StrategyErrorKind.INVALID_STATE,
                "universe is immutable after READY",
            )
        }
        return run
    }

    private suspend fun insertUniverseLocked(runId: Long, instrumentId: Long) {
        if (universeDao.exists(runId, instrumentId)) return
        universeDao.insert(
            StrategyRunInstrumentEntity(
                strategyRunId = runId,
                instrumentId = instrumentId,
                createdAt = now(),
            ),
        )
    }

    suspend fun findById(id: Long) = strategyRunDao.findById(id)

    suspend fun findAll() = strategyRunDao.findAll()
}
