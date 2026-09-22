package com.mirunubi.bjstock

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import dagger.hilt.android.HiltAndroidApp
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Auto scheduler remains OFF unless the user previously enabled it.
        forwardTestScheduler.reconcileOnAppStart()
        applicationScope.launch {
            runCatching { apiErrorLogService.cleanupOlderThanSevenDays() }
        }
    }
}
