package com.mirunubi.bjstock.probe.intraday

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Runs on the repository's Robolectric SDK 28 configuration; no device, no network. */
@RunWith(RobolectricTestRunner::class)
class ProbeAndroidPlatformTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()

    // F-3: below API 33 the network-time reference is unavailable; nothing throws and nothing is invented.
    @Test
    fun networkTimeBelowApi33IsUnavailableWithoutThrowing() {
        val reading = AndroidProbePlatform(app).networkTimeReading()
        assertEquals(28, reading.sdkInt)
        assertNull(reading.networkEpochMillis)
        assertEquals("API_BELOW_33", reading.unavailableReason)
    }

    // F-3 / M-20: wall-clock and timezone changes are recorded and trigger a network-time sample.
    @Test
    fun clockChangeBroadcastsAreObservedAndUnregisteredOnStop() = runBlocking {
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val platform = AndroidProbePlatform(app)
        val sessionId = ProbeSessionId.create(1_790_000_000_000L)
        val store = ProbeEvidenceStore(temp.root, SecretScrubber())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = ProbeRecorder(sessionId, store, AndroidProbeClock) { fail("storage failed") }.also { it.start(scope) }
        val reasons = mutableListOf<NetworkTimeSampleReason>()

        fun isProbeReceiver(filter: android.content.IntentFilter) =
            filter.hasAction(Intent.ACTION_TIME_CHANGED) && filter.hasAction(Intent.ACTION_SCREEN_OFF)

        assertTrue(shadowOf(app).registeredReceivers.none { isProbeReceiver(it.intentFilter) })
        platform.startObserving(recorder) { reasons += it }
        val wrapper = shadowOf(app).registeredReceivers.single { isProbeReceiver(it.intentFilter) }
        assertTrue(wrapper.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED))
        wrapper.broadcastReceiver.onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        wrapper.broadcastReceiver.onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra("time-zone", "Asia/Seoul"))
        platform.stopObserving()
        recorder.close()
        scope.cancel()

        assertTrue(shadowOf(app).registeredReceivers.none { isProbeReceiver(it.intentFilter) })
        assertEquals(listOf(NetworkTimeSampleReason.TIME_CHANGED, NetworkTimeSampleReason.TIMEZONE_CHANGED), reasons)
        val events = store.files(sessionId).events.readLines().map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["event_type"]!!.jsonPrimitive.content in setOf("WALL_CLOCK_CHANGED", "TIMEZONE_CHANGED") }
        assertEquals(listOf("WALL_CLOCK_CHANGED", "TIMEZONE_CHANGED"), events.map { it["event_type"]!!.jsonPrimitive.content })
        assertEquals("Asia/Seoul", events[1]["time_zone"]!!.jsonPrimitive.content)
    }
}
