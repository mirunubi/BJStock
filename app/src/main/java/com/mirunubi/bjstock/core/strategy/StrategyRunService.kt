package com.mirunubi.bjstock.core.strategy

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import java.time.Instant
import java.time.LocalDate

class StrategyRunService(
    private val database: BJStockDatabase,
    private val strategyDao: StrategyDao,
    private val strategyRunDao: StrategyRunDao,
    private val cashLedger: CashLedgerService,
    private val policyService: PaperTradingPolicyService,
    private val defaultPolicyTemplate: () -> PaperTradingPolicy = { PaperTradingPolicy.DEFAULT },
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun createReadyRun(
        strategyVersionId: Long,
        runName: String,
        startDate: LocalDate,
        initialCashWon: Long,
        policyTemplate: PaperTradingPolicy = defaultPolicyTemplate(),
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
            runId
        }
    }

    suspend fun findById(id: Long) = strategyRunDao.findById(id)

    suspend fun findAll() = strategyRunDao.findAll()
}
