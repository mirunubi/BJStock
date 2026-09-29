package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily wake-up only. Execution goes through [ForwardTestExecutionCoordinator];
 * all market-date logic lives in [ForwardTestOrchestrator].
 */
@HiltWorker
class ForwardTestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: ForwardTestExecutionCoordinator,
    private val settings: ForwardTestSchedulerSettings,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.isAutoEnabled()) {
            return Result.success()
        }
        val outcome = coordinator.runWorker(workId = id.toString(), runAttempt = runAttemptCount)
        return when (outcome.disposition) {
            WorkerDisposition.SUCCESS -> Result.success()
            WorkerDisposition.RETRY -> Result.retry()
            WorkerDisposition.FAILURE -> Result.failure()
        }
    }
}
