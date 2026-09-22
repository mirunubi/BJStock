package com.mirunubi.bjstock

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BJStockApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var forwardTestScheduler: ForwardTestScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Auto scheduler remains OFF unless the user previously enabled it.
        forwardTestScheduler.reconcileOnAppStart()
    }
}
