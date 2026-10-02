package com.mirunubi.bjstock.probe.intraday

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.time.DateTimeException
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AndroidProbeClock : ProbeClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Observes platform state only. It never changes a device setting or requests a battery-optimization exemption. */
class AndroidProbePlatform(private val context: Context) : ProbePlatform {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun evidenceBaseDir(): File =
        context.getExternalFilesDir(ProbeEvidenceStore.DIRECTORY_NAME)
            ?: throw ProbeStorageException("external app-specific storage unavailable")

    /** Reference observation only. No other time source is consulted when Android has no network time. */
    override fun networkTimeReading(): NetworkTimeReading {
        val sdk = Build.VERSION.SDK_INT
        val before = SystemClock.elapsedRealtimeNanos()
        val wall = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NetworkTimeReading(sdk, wall, before, before, null, "API_BELOW_33")
        }
        return try {
            val network = SystemClock.currentNetworkTimeClock().millis()
            NetworkTimeReading(sdk, wall, before, SystemClock.elapsedRealtimeNanos(), network, null)
        } catch (error: DateTimeException) {
            NetworkTimeReading(sdk, wall, before, SystemClock.elapsedRealtimeNanos(), null, "NETWORK_TIME_UNAVAILABLE")
        } catch (error: RuntimeException) {
            NetworkTimeReading(sdk, wall, before, SystemClock.elapsedRealtimeNanos(), null, error.javaClass.simpleName)
        }
    }

    fun appInfo(): JsonObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildJsonObject {
            put("package", context.packageName)
            put("version_name", info.versionName ?: "")
            put("version_code", info.longVersionCode)
            put("debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            put("target_sdk", context.applicationInfo.targetSdkVersion)
        }
    }

    override fun deviceMetadata(): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("fingerprint", Build.FINGERPRINT)
    }

    override fun stateSnapshot(): JsonObject = buildJsonObject {
        put("notification_permission_granted", notificationPermissionGranted())
        put("notifications_enabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        put("ignoring_battery_optimizations", powerManager.isIgnoringBatteryOptimizations(context.packageName))
        put("screen_interactive", powerManager.isInteractive)
        put("device_idle_mode", powerManager.isDeviceIdleMode)
        put("power_save_mode", powerManager.isPowerSaveMode)
        put("background_restricted", activityManager.isBackgroundRestricted)
        put("app_standby_bucket", context.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
        put("restrict_background_status", connectivityManager.restrictBackgroundStatus)
        put("network", networkSummary(connectivityManager.activeNetwork))
    }

    override fun batterySample(): JsonObject {
        val sticky = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return buildJsonObject {
            put("capacity_percent", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charge_counter_uah", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            put("current_now_ua", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            put("charging", batteryManager.isCharging)
            if (sticky != null) {
                put("status", sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                put("plugged", sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                put("temperature_decicelsius", sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE))
                put("voltage_mv", sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            }
        }
    }

    override fun thermalSample(): JsonObject = buildJsonObject {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put("thermal_status", powerManager.currentThermalStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val headroom = powerManager.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
            if (!headroom.isNaN()) put("thermal_headroom_10s", headroom.toDouble())
        }
    }

    override fun trafficSample(): JsonObject {
        val uid = Process.myUid()
        return buildJsonObject {
            put("uid_rx_bytes", TrafficStats.getUidRxBytes(uid))
            put("uid_tx_bytes", TrafficStats.getUidTxBytes(uid))
        }
    }

    override fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun previousProcessExits(): List<JsonObject> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS).map { exit ->
            buildJsonObject {
                put("exit_timestamp_epoch_ms", exit.timestamp)
                put("reason_code", exit.reason)
                put("reason_name", exitReasonName(exit.reason))
                put("status", exit.status)
                put("importance", exit.importance)
                put("pss_kb", exit.pss)
                put("rss_kb", exit.rss)
                put("description", exit.description?.take(MAX_DESCRIPTION) ?: "")
                put("memory_limiter_suspected", ProcessExitEvidence.memoryLimiterSuspected(exit.reason, exit.description))
            }
        }
    }

    override fun startObserving(recorder: ProbeRecorder, onClockChanged: (NetworkTimeSampleReason) -> Unit) {
        stopObserving()
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_TIME_CHANGED -> {
                        recorder.record(ProbeEventType.WALL_CLOCK_CHANGED, buildJsonObject { put("trigger", "TIME_SET") })
                        onClockChanged(NetworkTimeSampleReason.TIME_CHANGED)
                    }
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        recorder.record(
                            ProbeEventType.TIMEZONE_CHANGED,
                            buildJsonObject {
                                put("trigger", "TIMEZONE_CHANGED")
                                put("time_zone", intent.getStringExtra(EXTRA_TIME_ZONE) ?: TimeZone.getDefault().id)
                            },
                        )
                        onClockChanged(NetworkTimeSampleReason.TIMEZONE_CHANGED)
                    }
                    Intent.ACTION_SCREEN_ON -> recorder.record(ProbeEventType.SCREEN_ON)
                    Intent.ACTION_SCREEN_OFF -> recorder.record(ProbeEventType.SCREEN_OFF)
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                    -> recorder.record(
                        ProbeEventType.IDLE_MODE_CHANGED,
                        buildJsonObject {
                            put("trigger", intent.action.orEmpty().substringAfterLast('.'))
                            put("device_idle_mode", powerManager.isDeviceIdleMode)
                            put("power_save_mode", powerManager.isPowerSaveMode)
                            put("screen_interactive", powerManager.isInteractive)
                        },
                    )
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED,
                    -> recorder.record(ProbeEventType.BATTERY_SAMPLE, batterySample())
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, broadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = broadcastReceiver

        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastSummary: JsonObject? = null

            override fun onAvailable(network: Network) {
                recorder.record(ProbeEventType.NETWORK_AVAILABLE, networkSummary(network))
            }

            override fun onLost(network: Network) {
                lastSummary = null
                recorder.record(ProbeEventType.NETWORK_LOST)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val summary = capabilitiesSummary(networkCapabilities)
                if (summary != lastSummary) {
                    lastSummary = summary
                    recorder.record(ProbeEventType.NETWORK_CAPABILITIES_CHANGED, summary)
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                recorder.record(ProbeEventType.NETWORK_CAPABILITIES_CHANGED, buildJsonObject { put("blocked", blocked) })
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        networkCallback = callback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                recorder.record(ProbeEventType.THERMAL_SAMPLE, buildJsonObject { put("thermal_status", status) })
            }
            powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            thermalListener = listener
        }
    }

    override fun stopObserving() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { runCatching { powerManager.removeThermalStatusListener(it) } }
        }
        thermalListener = null
    }

    private fun networkSummary(network: Network?): JsonObject {
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            ?: return buildJsonObject { put("available", false) }
        return capabilitiesSummary(capabilities)
    }

    private fun capabilitiesSummary(capabilities: NetworkCapabilities): JsonObject = buildJsonObject {
        put("available", true)
        put("transport_wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        put("transport_cellular", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        put("transport_ethernet", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        put("transport_vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        put("internet", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        put("not_metered", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN_$reason"
    }

    private companion object {
        const val MAX_EXIT_RECORDS = 5
        const val MAX_DESCRIPTION = 200
        const val THERMAL_FORECAST_SECONDS = 10
        const val EXTRA_TIME_ZONE = "time-zone"
    }
}
