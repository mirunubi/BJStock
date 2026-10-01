package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.core.analytics.DailyPerformancePoint
import com.mirunubi.bjstock.core.analytics.MonthlyPerformance
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.RunComparisonRow
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject

data class PerformanceRunData(
    val run: StrategyRunEntity,
    val strategyName: String,
    val versionLabel: String,
    val summary: RunPerformanceSummary,
)

data class PerformanceDetailData(
    val run: PerformanceRunData,
    /** Chronological; empty before the first snapshot and when the summary is DATA_ERROR. */
    val series: List<DailyPerformancePoint>,
    val monthly: List<MonthlyPerformance>,
)

/**
 * Read-only data for the 성과 tab. It has no write function: every value comes from
 * [PerformanceAnalyticsService] / [PerformanceAnalyticsRepository], which never write and never call the network.
 */
interface PerformanceDataSource {
    suspend fun runs(): List<PerformanceRunData>

    suspend fun detail(runId: Long): PerformanceDetailData?

    suspend fun compare(runIds: List<Long>): List<RunComparisonRow>
}

class RoomPerformanceDataSource @Inject constructor(
    private val repository: PerformanceAnalyticsRepository,
    private val analytics: PerformanceAnalyticsService,
) : PerformanceDataSource {
    override suspend fun runs(): List<PerformanceRunData> = repository.loadAllRuns().map { runData(it) }

    override suspend fun detail(runId: Long): PerformanceDetailData? {
        val run = repository.loadRun(runId) ?: return null
        val data = runData(run)
        if (data.summary.status == PerformanceStatus.DATA_ERROR) {
            return PerformanceDetailData(data, series = emptyList(), monthly = emptyList())
        }
        return PerformanceDetailData(
            run = data,
            series = analytics.calculateDailySeries(runId),
            monthly = analytics.calculateMonthlyReturns(runId),
        )
    }

    override suspend fun compare(runIds: List<Long>): List<RunComparisonRow> = analytics.compareRuns(runIds)

    private suspend fun runData(run: StrategyRunEntity): PerformanceRunData {
        val (strategyName, versionLabel) = repository.loadStrategyLabel(run)
        return PerformanceRunData(run, strategyName, versionLabel, analytics.calculateSummary(run.id))
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class PerformanceScreenModule {
    @Binds
    abstract fun bindPerformanceDataSource(source: RoomPerformanceDataSource): PerformanceDataSource
}
