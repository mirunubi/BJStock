package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
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
    private val scheduler: ForwardTestScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val disposition = runAutoInvocation(
            workId = id.toString(),
            runAttempt = runAttemptCount,
            input = inputData,
            scheduler = scheduler,
            runWorker = coordinator::runWorker,
        )
        return when (disposition) {
            WorkerDisposition.SUCCESS -> Result.success()
            WorkerDisposition.RETRY -> Result.retry()
            WorkerDisposition.FAILURE -> Result.failure()
        }
    }
}

/**
 * One Auto invocation (docs/150 §20.9.6): Auto OFF exits quietly; input without a valid schedule instance
 * (legacy periodic work) never executes and only moves scheduling to v2; a valid slot schedules the following
 * slot first, so success, failure and retry all leave exactly one future slot behind.
 */
suspend fun runAutoInvocation(
    workId: String,
    runAttempt: Int,
    input: Data,
    scheduler: ForwardTestScheduler,
    runWorker: suspend (workId: String, runAttempt: Int, scheduleInstanceId: String) -> ForwardOperationOutcome,
): WorkerDisposition {
    if (!scheduler.isAutoEnabled()) return WorkerDisposition.SUCCESS
    val slot = AutoWorkRequests.slotOf(input)
    if (slot == null) {
        scheduler.migrateFromLegacyInvocation()
        return WorkerDisposition.SUCCESS
    }
    scheduler.ensureSlotAfter(slot)
    return runWorker(workId, runAttempt, slot.scheduleInstanceId).disposition
}
