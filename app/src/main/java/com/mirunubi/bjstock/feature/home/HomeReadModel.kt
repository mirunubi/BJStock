package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.OpenPositionView
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.database.dao.ForwardOperationDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.model.RunStatus
import java.time.Instant
import javax.inject.Inject

/** Raw read data for Home; presentation happens in [HomePresenter]. */
data class HomeSnapshot(
    val run: HomeRunData?,
    val candidateRunCount: Int,
    val auto: AutoScheduleStatus,
    val latestOperation: ForwardOperationEntity?,
    val now: Instant,
)

data class HomeRunData(
    val run: StrategyRunEntity,
    val summary: RunPerformanceSummary,
    val positions: List<OpenPositionView>,
    val instrumentNames: Map<Long, String>,
    val latestEvaluation: StockEvaluationEntity?,
    val latestEvaluationInstrument: InstrumentEntity?,
    val sameDayEvaluationCount: Int,
)

/**
 * Temporary UI-1 rule for which Run Home shows (docs/151 §21): DRAFT and CANCELLED Runs are excluded;
 * the rest are ordered RUNNING, READY, PAUSED, COMPLETED, then by the highest id (most recently created).
 */
object HomeRunSelection {
    private val RANK = mapOf(
        RunStatus.RUNNING to 0,
        RunStatus.READY to 1,
        RunStatus.PAUSED to 2,
        RunStatus.COMPLETED to 3,
    )

    fun candidates(runs: List<StrategyRunEntity>): List<StrategyRunEntity> =
        runs.filter { it.status in RANK }
            .sortedWith(compareBy<StrategyRunEntity> { RANK.getValue(it.status) }.thenByDescending { it.id })

    fun select(runs: List<StrategyRunEntity>): StrategyRunEntity? = candidates(runs).firstOrNull()
}

fun interface HomeReadModelLoader {
    suspend fun load(): HomeSnapshot
}

/** Composes existing read APIs only; performs no calculation of its own and no writes. */
class RoomHomeReadModelLoader @Inject constructor(
    private val repository: PerformanceAnalyticsRepository,
    private val analytics: PerformanceAnalyticsService,
    private val scheduler: ForwardTestScheduler,
    private val instrumentDao: InstrumentDao,
    private val operationDao: ForwardOperationDao,
    private val clock: ForwardTestClock,
) : HomeReadModelLoader {
    override suspend fun load(): HomeSnapshot {
        val candidates = HomeRunSelection.candidates(repository.loadAllRuns())
        return HomeSnapshot(
            run = candidates.firstOrNull()?.let { loadRun(it) },
            candidateRunCount = candidates.size,
            auto = scheduler.status(),
            latestOperation = operationDao.findRecent(limit = 1).firstOrNull(),
            now = clock.nowInstant(),
        )
    }

    private suspend fun loadRun(run: StrategyRunEntity): HomeRunData {
        val positions = analytics.loadOpenPositionViews(run.id)
        val evaluations = repository.loadEvaluations(run.id)
        val latest = evaluations.lastOrNull()
        return HomeRunData(
            run = run,
            summary = analytics.calculateSummary(run.id),
            positions = positions,
            instrumentNames = positions.associate { position ->
                position.instrumentId to (instrumentDao.findById(position.instrumentId)?.name ?: position.symbol)
            },
            latestEvaluation = latest,
            latestEvaluationInstrument = latest?.let { instrumentDao.findById(it.instrumentId) },
            sameDayEvaluationCount = latest?.let { l -> evaluations.count { it.evaluationDate == l.evaluationDate } } ?: 0,
        )
    }
}
