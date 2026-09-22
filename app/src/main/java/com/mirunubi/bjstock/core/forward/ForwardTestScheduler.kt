package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Registers / cancels unique periodic forward-test wake-ups.
 * Default auto setting is OFF — does not enqueue unless explicitly enabled.
 */
class ForwardTestScheduler(
    private val context: Context,
    private val settings: ForwardTestSchedulerSettings,
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    fun isAutoEnabled(): Boolean = settings.isAutoEnabled()

    fun setAutoEnabled(enabled: Boolean) {
        settings.setAutoEnabled(enabled)
        if (enabled) {
            enqueuePeriodic()
        } else {
            cancelPeriodic()
        }
    }

    /** Re-apply schedule from persisted preference (e.g. process start). Never enables by default. */
    fun reconcileOnAppStart() {
        if (settings.isAutoEnabled()) {
            enqueuePeriodic()
        }
    }

    fun isPeriodicEnqueued(): Boolean =
        try {
            workManager.getWorkInfosForUniqueWork(ForwardTestConfig.UNIQUE_WORK_NAME)
                .get()
                .any { !it.state.isFinished }
        } catch (_: Exception) {
            false
        }

    private fun enqueuePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ForwardTestWorker>(
            1,
            TimeUnit.DAYS,
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            ForwardTestConfig.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelPeriodic() {
        workManager.cancelUniqueWork(ForwardTestConfig.UNIQUE_WORK_NAME)
    }
}
