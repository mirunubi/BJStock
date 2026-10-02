package com.mirunubi.bjstock.probe.intraday

import com.mirunubi.bjstock.core.kis.KisCredentials
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Unit-test fixtures only. These fake values are never real credentials and never reach a provider. */
object ProbeFixtures {
    const val FAKE_APP_KEY = "FAKE-APPKEY-0000-unit-test-only"
    const val FAKE_APP_SECRET = "FAKE-APPSECRET-1111-unit-test-only"
    const val FAKE_APPROVAL_KEY = "FAKE-APPROVAL-2222-unit-test-only"
    const val FAKE_ACCESS_TOKEN = "FAKE-ACCESS-TOKEN-3333-unit-test-only"
    val CREDENTIALS = KisCredentials(FAKE_APP_KEY, FAKE_APP_SECRET)
    val ALL_SECRETS = listOf(FAKE_APP_KEY, FAKE_APP_SECRET, FAKE_APPROVAL_KEY, FAKE_ACCESS_TOKEN)

    /** Builds one 47-column H0STCNT0 test record; all other columns are filler. */
    fun record(symbol: String, hhmmss: String, price: String, businessDate: String = "20261002"): List<String> {
        val fields = MutableList(H0stcnt0Columns.WIDTH) { "0" }
        fields[H0stcnt0Columns.index("MKSC_SHRN_ISCD")] = symbol
        fields[H0stcnt0Columns.index("STCK_CNTG_HOUR")] = hhmmss
        fields[H0stcnt0Columns.index("STCK_PRPR")] = price
        fields[H0stcnt0Columns.index("BSOP_DATE")] = businessDate
        return fields
    }

    fun frame(vararg records: List<String>, flag: String = "0", trId: String = "H0STCNT0", count: String? = null): String =
        "$flag|$trId|${count ?: records.size}|" + records.flatMap { it }.joinToString("^")

    fun readAll(dir: File): String =
        dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
}

class FakeClock(var wall: Long = 1_790_000_000_000L, var nanos: Long = 1_000_000_000L) : ProbeClock {
    override fun wallMillis(): Long = wall
    override fun elapsedRealtimeNanos(): Long = nanos
}

class FakeSocket : ProbeSocket {
    val sent = CopyOnWriteArrayList<String>()
    var closed = false

    override fun send(text: String): Boolean {
        sent += text
        return true
    }

    override fun close(code: Int, reason: String) {
        closed = true
    }
}

class FakeNetwork(
    private val approvalFailure: Exception? = null,
    private val minuteBarFailure: Exception? = null,
) : ProbeNetwork {
    val sockets = CopyOnWriteArrayList<FakeSocket>()
    val callbacks = CopyOnWriteArrayList<ProbeSocketCallbacks>()
    val approvalCalls = AtomicInteger()
    val tokenCalls = AtomicInteger()
    val minuteBarCalls = AtomicInteger()

    /** When set, each minute-bar request stays in flight until the gate completes. */
    @Volatile
    var minuteBarGate: CompletableDeferred<Unit>? = null

    override suspend fun issueToken(credentials: KisCredentials): ProbeSecret {
        tokenCalls.incrementAndGet()
        return ProbeSecret(ProbeFixtures.FAKE_ACCESS_TOKEN)
    }

    override suspend fun requestApprovalKey(credentials: KisCredentials): ProbeSecret {
        approvalCalls.incrementAndGet()
        approvalFailure?.let { throw it }
        return ProbeSecret(ProbeFixtures.FAKE_APPROVAL_KEY)
    }

    override suspend fun fetchTodayMinuteBars(
        token: ProbeSecret,
        credentials: KisCredentials,
        symbol: String,
        inputHourHhmmss: String,
    ): MinuteBarObservation {
        minuteBarCalls.incrementAndGet()
        minuteBarGate?.await()
        minuteBarFailure?.let { throw it }
        return MinuteBarObservation(200, "0", "MCA00000", "ok", 0, null, null, null)
    }

    override fun connect(callbacks: ProbeSocketCallbacks): ProbeSocket {
        this.callbacks += callbacks
        return FakeSocket().also { sockets += it }
    }
}

class FakeCredentialSource(private val credentials: KisCredentials?) : ProbeCredentialSource {
    val loadCalls = AtomicInteger()
    val hasCalls = AtomicInteger()

    override suspend fun hasVirtualCredentials(): Boolean {
        hasCalls.incrementAndGet()
        return credentials != null
    }

    override suspend fun loadVirtualCredentials(): KisCredentials? {
        loadCalls.incrementAndGet()
        return credentials
    }

    override suspend fun loadReusableVirtualToken(): ProbeSecret? = null
}

class FakePlatform(
    private val baseDir: File,
    private val storageFailure: Exception? = null,
    var networkTimeMillis: Long? = null,
    private val clock: FakeClock = FakeClock(),
) : ProbePlatform {
    val observing = AtomicLong()

    @Volatile
    var clockChanged: ((NetworkTimeSampleReason) -> Unit)? = null

    override fun evidenceBaseDir(): File {
        storageFailure?.let { throw it }
        return baseDir
    }

    override fun networkTimeReading(): NetworkTimeReading = NetworkTimeReading(
        sdkInt = 36,
        wallMillis = clock.wall,
        elapsedNanosBefore = clock.nanos,
        elapsedNanosAfter = clock.nanos + 1_000,
        networkEpochMillis = networkTimeMillis,
        unavailableReason = if (networkTimeMillis == null) "NETWORK_TIME_UNAVAILABLE" else null,
    )

    override fun deviceMetadata(): JsonObject = buildJsonObject { put("model", "unit-test") }
    override fun stateSnapshot(): JsonObject = buildJsonObject { put("screen_interactive", true) }
    override fun batterySample(): JsonObject = buildJsonObject { put("capacity_percent", 80) }
    override fun thermalSample(): JsonObject = buildJsonObject { put("thermal_status", 0) }
    override fun trafficSample(): JsonObject = buildJsonObject { put("uid_rx_bytes", 0) }
    override fun notificationPermissionGranted(): Boolean = true
    override fun previousProcessExits(): List<JsonObject> = emptyList()
    override fun startObserving(recorder: ProbeRecorder, onClockChanged: (NetworkTimeSampleReason) -> Unit) {
        observing.incrementAndGet()
        clockChanged = onClockChanged
    }
    override fun stopObserving() {
        if (observing.get() > 0) observing.decrementAndGet()
        clockChanged = null
    }
}
