package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import java.time.Instant
import java.time.LocalDate

class StrategyRunService(
    private val strategyDao: StrategyDao,
    private val strategyRunDao: StrategyRunDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun createReadyRun(
        strategyVersionId: Long,
        runName: String,
        startDate: LocalDate,
        initialCashWon: Long,
    ): Long {
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
        return strategyRunDao.insert(
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
    }

    suspend fun findById(id: Long) = strategyRunDao.findById(id)
}
