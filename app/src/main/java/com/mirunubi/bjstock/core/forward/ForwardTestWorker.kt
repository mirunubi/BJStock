package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily wake-up only. All market-date logic lives in [ForwardTestOrchestrator].
 */
@HiltWorker
class ForwardTestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: ForwardTestOrchestrator,
    private val settings: ForwardTestSchedulerSettings,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.isAutoEnabled()) {
            return Result.success()
        }
        return when (val outcome = orchestrator.runForwardTests()) {
            is ForwardOrchestratorResult.Ok,
            is ForwardOrchestratorResult.NoOp,
            -> Result.success()
            is ForwardOrchestratorResult.Blocked -> {
                if (outcome.retryable) Result.retry() else Result.failure()
            }
        }
    }
}
