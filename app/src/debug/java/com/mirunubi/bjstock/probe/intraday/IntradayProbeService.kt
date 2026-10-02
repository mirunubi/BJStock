package com.mirunubi.bjstock.probe.intraday

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisTokenStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debug-only, user-started foreground service for the isolated 12-B2 probe. START_NOT_STICKY plus the null-intent
 * guard mean a process death never creates a new session; there is no boot, alarm, or WorkManager entry point.
 */
@AndroidEntryPoint
class IntradayProbeService : Service() {
    @Inject lateinit var credentialStore: KisCredentialStore

    @Inject lateinit var tokenStore: KisTokenStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private lateinit var platform: AndroidProbePlatform
    private lateinit var controller: ProbeSessionController
    private lateinit var notifications: ProbeNotificationLifecycle

    override fun onCreate() {
        super.onCreate()
        platform = AndroidProbePlatform(applicationContext)
        controller = ProbeSessionController(
            credentialSource = StoreBackedProbeCredentialSource(credentialStore, tokenStore),
            platform = platform,
            clock = AndroidProbeClock,
            appInfo = platform.appInfo(),
        )
        notifications = ProbeNotificationLifecycle(
            post = ::updateNotification,
            cancel = { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) },
        )
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getBooleanExtra(EXTRA_REST_MINUTE_BARS, false))
            ACTION_STOP -> scope.launch { stopProbe("USER_STOP") }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleStart(restMinuteBars: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ProbeStateHolder.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch {
            lifecycleMutex.withLock {
                notifications.start(scope, ProbeStateHolder.state)
                when (controller.start(scope, ProbeStartOptions(restMinuteBars), ProbeScope.CANDIDATE_FGS_TYPE)) {
                    ProbeStartOutcome.STARTED, ProbeStartOutcome.REFUSED_ACTIVE_SESSION -> Unit
                    ProbeStartOutcome.REFUSED_STORAGE, ProbeStartOutcome.REFUSED_GATE -> endForeground()
                }
            }
        }
    }

    /** Valid from every state, including ERROR and a refused start with no session. */
    private suspend fun stopProbe(reason: String) = lifecycleMutex.withLock {
        controller.stop(reason)
        endForeground()
    }

    private suspend fun endForeground() {
        notifications.shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.recordForegroundTimeout(fgsType)
        scope.launch { stopProbe("FGS_TIMEOUT") }
    }

    override fun onDestroy() {
        val pending = scope.launch {
            lifecycleMutex.withLock {
                controller.stop("SERVICE_DESTROYED")
                notifications.shutdown()
            }
        }
        pending.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "BJStock 12-B2 Probe", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Debug-only isolated market-data probe status. No trading."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(state: ProbeUiState) {
        if (!platform.notificationPermissionGranted()) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun buildNotification(state: ProbeUiState): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, IntradayProbeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, IntradayProbeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BJStock 12-B2 Probe")
            .setContentText("VIRTUAL / KRX / NO TRADING — ${state.status.name}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "VIRTUAL / KRX / NO TRADING\nState: ${state.status.name}\nRecords: ${state.records}  Reconnects: ${state.reconnects}",
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.mirunubi.bjstock.probe.intraday.action.START"
        const val ACTION_STOP = "com.mirunubi.bjstock.probe.intraday.action.STOP"
        const val EXTRA_REST_MINUTE_BARS = "rest_minute_bars"
        private const val CHANNEL_ID = "bjstock_intraday_probe"
        private const val NOTIFICATION_ID = 12_002

        fun startIntent(context: Context, restMinuteBars: Boolean): Intent =
            Intent(context, IntradayProbeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REST_MINUTE_BARS, restMinuteBars)

        fun stopIntent(context: Context): Intent =
            Intent(context, IntradayProbeService::class.java).setAction(ACTION_STOP)
    }
}
