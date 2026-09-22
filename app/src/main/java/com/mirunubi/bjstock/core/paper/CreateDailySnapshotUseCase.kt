package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.PortfolioDailySnapshotDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.PortfolioDailySnapshotEntity
import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

class CreateDailySnapshotUseCase(
    private val strategyRunDao: StrategyRunDao,
    private val positionDao: PositionDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val snapshotDao: PortfolioDailySnapshotDao,
    private val cashLedger: CashLedgerService,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend operator fun invoke(
        strategyRunId: Long,
        snapshotDate: LocalDate,
    ): PaperTradeResult {
        val run = strategyRunDao.findById(strategyRunId)
            ?: return PaperTradeResult(PaperTradeAction.SNAPSHOT_FAILED, "run not found")

        val existing = snapshotDao.find(strategyRunId, snapshotDate)
        if (existing != null) {
            return PaperTradeResult(
                action = PaperTradeAction.SNAPSHOT_ALREADY_EXISTS,
                snapshotId = existing.id,
                message = "ALREADY_EXISTS",
            )
        }

        val cash = cashLedger.currentCash(strategyRunId)
        val openPositions = positionDao.findOpenByRun(strategyRunId)
        var marketValue = 0L
        for (position in openPositions) {
            val bar = marketDailyBarDao.findByInstrumentAndDate(position.instrumentId, snapshotDate)
                ?: return PaperTradeResult(
                    action = PaperTradeAction.SNAPSHOT_FAILED,
                    message = "SNAPSHOT_FAIL missing close for instrument ${position.instrumentId} on $snapshotDate",
                )
            marketValue += position.quantity * bar.closePrice
        }

        val totalAsset = cash + marketValue
        val previous = snapshotDao.findByRun(strategyRunId)
            .filter { it.snapshotDate < snapshotDate }
            .maxByOrNull { it.snapshotDate }
        val baseline = previous?.totalAsset ?: run.initialCash
        val dailyProfit = totalAsset - baseline
        val dailyReturn = ratioStored(dailyProfit, baseline)
        val cumulativeProfit = totalAsset - run.initialCash
        val cumulativeReturn = ratioStored(cumulativeProfit, run.initialCash)
        val priorPeak = snapshotDao.findPeakTotalAsset(strategyRunId, snapshotDate.minusDays(1))
            ?: run.initialCash
        val peak = maxOf(priorPeak, totalAsset)
        val drawdown = if (peak <= 0L) {
            0L
        } else {
            ratioStored(totalAsset - peak, peak)
        }

        val id = snapshotDao.insert(
            PortfolioDailySnapshotEntity(
                strategyRunId = strategyRunId,
                snapshotDate = snapshotDate,
                cash = cash,
                marketValue = marketValue,
                totalAsset = totalAsset,
                dailyProfit = dailyProfit,
                dailyReturn = dailyReturn,
                cumulativeProfit = cumulativeProfit,
                cumulativeReturn = cumulativeReturn,
                drawdown = drawdown,
                createdAt = now(),
            ),
        )
        return PaperTradeResult(
            action = PaperTradeAction.SNAPSHOT_CREATED,
            snapshotId = id,
        )
    }

    companion object {
        fun ratioStored(numerator: Long, denominator: Long): Long {
            if (denominator == 0L) return 0L
            return BigDecimal(numerator)
                .divide(BigDecimal(denominator), NumericMapping.RATIO_SCALE + 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(NumericMapping.RATIO_FACTOR))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }
    }
}
