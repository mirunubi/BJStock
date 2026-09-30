package com.mirunubi.bjstock

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.runAppStartSequence
import dagger.hilt.android.HiltAndroidApp
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class BJStockApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var forwardTestScheduler: ForwardTestScheduler

    @Inject
    lateinit var apiErrorLogService: ApiErrorLogService

    @Inject
    lateinit var forwardOperationLog: ForwardOperationLogService

    /**
     * Captured before any work of this process can start. Truncated to millis because `started_at` is stored
     * as epoch millis: an operation started by this process is always stored at or after the cutoff.
     */
    private val processStartCutoff: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runAppStartSequence(
                recoverInterruptedOperations = { forwardOperationLog.recoverInterruptedOperations(processStartCutoff) },
                // Auto scheduler remains OFF unless the user previously enabled it.
                reconcileAutoSchedule = { forwardTestScheduler.reconcileOnAppStart() },
                maintenance = { apiErrorLogService.cleanupOlderThanSevenDays() },
            )
        }
    }
}
