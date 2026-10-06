package com.mirunubi.bjstock.probe.intraday

import com.mirunubi.bjstock.core.kis.KisCredentials
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisTokenStore
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All lookups are hard-wired to [KisEnvironment.VIRTUAL]; there is no selected-environment lookup. */
interface ProbeCredentialSource {
    suspend fun hasVirtualCredentials(): Boolean
    suspend fun loadVirtualCredentials(): KisCredentials?
    suspend fun loadReusableVirtualToken(): ProbeSecret?
}

class StoreBackedProbeCredentialSource(
    private val credentialStore: KisCredentialStore,
    private val tokenStore: KisTokenStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : ProbeCredentialSource {
    override suspend fun hasVirtualCredentials(): Boolean = credentialStore.hasCredentials(KisEnvironment.VIRTUAL)

    override suspend fun loadVirtualCredentials(): KisCredentials? = credentialStore.loadCredentials(KisEnvironment.VIRTUAL)

    override suspend fun loadReusableVirtualToken(): ProbeSecret? =
        tokenStore.loadToken(KisEnvironment.VIRTUAL)
            ?.takeIf { it.expiresAtEpochMillis > nowMillis() + TOKEN_REUSE_MARGIN_MILLIS }
            ?.let { ProbeSecret(it.accessToken) }

    private companion object {
        const val TOKEN_REUSE_MARGIN_MILLIS = 5 * 60 * 1000L
    }
}

interface ProbePlatform {
    /** App-specific evidence directory. May throw when external app storage is unavailable. */
    fun evidenceBaseDir(): File
    fun deviceMetadata(): JsonObject
    fun stateSnapshot(): JsonObject
    fun batterySample(): JsonObject
    fun thermalSample(): JsonObject
    fun trafficSample(): JsonObject
    fun networkTimeReading(): NetworkTimeReading
    fun notificationPermissionGranted(): Boolean
    fun previousProcessExits(): List<JsonObject>
    fun startObserving(recorder: ProbeRecorder, onClockChanged: (NetworkTimeSampleReason) -> Unit)
    fun stopObserving()
}

data class ProbeStartOptions(val restMinuteBarObservation: Boolean = false)

enum class ProbeStartOutcome {
    STARTED,
    REFUSED_ACTIVE_SESSION,
    REFUSED_STORAGE,
    REFUSED_GATE,
}

data class ProbeUiState(
    val status: ProbeStatus = ProbeStatus.STOPPED,
    val sessionActive: Boolean = false,
    val sessionId: String? = null,
    val evidenceDir: String? = null,
    val lastMessage: String = "Not started.",
    val records: Long = 0,
    val frames: Long = 0,
    val frameIssues: Long = 0,
    val reconnects: Long = 0,
    val heartbeatGaps: Long = 0,
    val possibleGaps: Long = 0,
) {
    /** ERROR is not terminated: Stop stays available while a session exists or the state is not STOPPED. */
    val canStop: Boolean get() = sessionActive || status != ProbeStatus.STOPPED

    val canStart: Boolean get() = !sessionActive && (status == ProbeStatus.STOPPED || status == ProbeStatus.ERROR)
}

object ProbeStateHolder {
    val state = MutableStateFlow(ProbeUiState())
}

class ProbeSessionController(
    private val credentialSource: ProbeCredentialSource,
    private val platform: ProbePlatform,
    private val clock: ProbeClock,
    private val appInfo: JsonObject,
    private val networkFactory: (VirtualGatePass) -> ProbeNetwork = { KisProbeTransport.create(it) },
    private val state: MutableStateFlow<ProbeUiState> = ProbeStateHolder.state,
    private val sessionIdFactory: (Long) -> String = { ProbeSessionId.create(it) },
) {
    private class ActiveSession(
        val sessionId: String,
        val store: ProbeEvidenceStore,
        val recorder: ProbeRecorder,
        val scrubber: SecretScrubber,
        val tracker: StreamContinuityTracker,
        val startWall: Long,
        val startNanos: Long,
        val startBattery: JsonObject,
        val startTraffic: JsonObject,
        val startThermal: JsonObject,
        val errors: MutableList<String>,
        val jobs: MutableList<Job> = mutableListOf(),
        @Volatile var stream: ProbeStreamSession? = null,
        @Volatile var networkJob: Job? = null,
        val policyDenied: AtomicBoolean = AtomicBoolean(false),
        @Volatile var policySource: String? = null,
    )

    private val mutex = Mutex()

    @Volatile
    private var active: ActiveSession? = null

    val isActive: Boolean get() = active != null

    suspend fun start(scope: CoroutineScope, options: ProbeStartOptions, fgsType: String): ProbeStartOutcome = mutex.withLock {
        if (active != null) {
            state.update { it.copy(lastMessage = "A probe session is still active (${it.status.name}). Stop it before starting a new one.") }
            return ProbeStartOutcome.REFUSED_ACTIVE_SESSION
        }
        state.update { it.copy(status = ProbeStatus.STARTING, sessionActive = false, lastMessage = "Starting.") }

        val baseDir = prepareStorage() ?: run {
            state.update { it.copy(status = ProbeStatus.ERROR, lastMessage = STORAGE_FAILED_MESSAGE) }
            return ProbeStartOutcome.REFUSED_STORAGE
        }

        val gate = VirtualGatePass.evaluate(ProbeScope.ENVIRONMENT, ProbeEndpoints.KIS_VIRTUAL) {
            credentialSource.hasVirtualCredentials()
        }
        val pass = when (gate) {
            is VirtualGatePass.Result.Refused -> {
                state.update { it.copy(status = ProbeStatus.STOPPED, lastMessage = "${gate.error.name}: ${gate.error.safeMessage}") }
                return ProbeStartOutcome.REFUSED_GATE
            }
            is VirtualGatePass.Result.Passed -> gate.pass
        }

        val startWall = clock.wallMillis()
        val startNanos = clock.elapsedRealtimeNanos()
        val sessionId = sessionIdFactory(startWall)
        val scrubber = SecretScrubber()
        val store = ProbeEvidenceStore(baseDir, scrubber)
        val startBattery = platform.batterySample()
        val startTraffic = platform.trafficSample()
        val startThermal = platform.thermalSample()
        try {
            store.writeMeta(sessionId, buildMeta(sessionId, startWall, startNanos, options, fgsType, startBattery, startTraffic, startThermal))
        } catch (error: ProbeStorageException) {
            state.update { it.copy(status = ProbeStatus.ERROR, lastMessage = STORAGE_FAILED_MESSAGE) }
            return ProbeStartOutcome.REFUSED_STORAGE
        }
        val errors = CopyOnWriteArrayList<String>()
        val recorder = ProbeRecorder(sessionId, store, clock) { markError(errors, ProbeErrorCode.PROBE_STORAGE_FAILED) }
        recorder.start(scope)
        val session = ActiveSession(
            sessionId, store, recorder, scrubber, StreamContinuityTracker(),
            startWall, startNanos, startBattery, startTraffic, startThermal, errors,
        )
        active = session
        state.value = ProbeUiState(
            status = ProbeStatus.STARTING,
            sessionActive = true,
            sessionId = sessionId,
            evidenceDir = baseDir.absolutePath,
            lastMessage = "Session started.",
        )

        recorder.record(ProbeEventType.SESSION_START, buildJsonObject { put("environment", ProbeScope.ENVIRONMENT.name) })
        recorder.record(ProbeEventType.FGS_START, buildJsonObject { put("fgs_type", fgsType) })
        sampleNetworkTime(session, NetworkTimeSampleReason.SESSION_START)
        platform.previousProcessExits().forEach { recorder.record(ProbeEventType.PREVIOUS_PROCESS_EXIT, it) }
        if (!platform.notificationPermissionGranted()) recordError(session, ProbeErrorCode.NOTIFICATION_PERMISSION_MISSING, updateStatus = false)
        platform.startObserving(recorder) { reason -> sampleNetworkTime(session, reason) }

        session.jobs += scope.launch { heartbeatLoop(session) }
        session.jobs += scope.launch { networkLoop(session, pass, options, scope) }
        ProbeStartOutcome.STARTED
    }

    /**
     * Valid from any state, including ERROR. Producers are quiesced first, then observers detached, then the
     * accepted event queue is drained, and only then is the summary derived from persisted counts.
     * With no session nothing is running, so an existing STOPPED or ERROR result (such as a pre-session
     * PROBE_STORAGE_FAILED refusal) is kept rather than replaced by a generic message.
     */
    suspend fun stop(reason: String) = mutex.withLock {
        val session = active ?: run {
            state.update {
                when (it.status) {
                    ProbeStatus.STOPPED -> it
                    ProbeStatus.ERROR -> it.copy(sessionActive = false)
                    else -> it.copy(status = ProbeStatus.STOPPED, sessionActive = false, lastMessage = "No active session.")
                }
            }
            return@withLock
        }
        state.update { it.copy(status = ProbeStatus.STOPPING, lastMessage = "Stopping.") }
        session.jobs.forEach { it.cancelAndJoin() }
        session.stream?.stopAndJoin()
        platform.stopObserving()

        val endBattery = platform.batterySample()
        val endTraffic = platform.trafficSample()
        val endThermal = platform.thermalSample()
        session.recorder.record(ProbeEventType.BATTERY_SAMPLE, endBattery)
        session.recorder.record(ProbeEventType.TRAFFIC_SAMPLE, endTraffic)
        session.recorder.record(ProbeEventType.THERMAL_SAMPLE, endThermal)
        sampleNetworkTime(session, NetworkTimeSampleReason.SESSION_STOP)
        session.recorder.record(ProbeEventType.FGS_STOP, buildJsonObject { put("reason", reason) })
        session.recorder.record(ProbeEventType.SESSION_STOP, buildJsonObject { put("reason", reason) })
        session.recorder.close()

        val endWall = clock.wallMillis()
        val endNanos = clock.elapsedRealtimeNanos()
        val summaryWritten = !session.recorder.storageFailed && runCatching {
            session.store.writeSummary(session.sessionId, buildSummary(session, reason, endWall, endNanos, endBattery, endTraffic, endThermal))
        }.isSuccess
        session.scrubber.clear()
        active = null
        state.update {
            it.copy(
                status = ProbeStatus.STOPPED,
                sessionActive = false,
                lastMessage = if (summaryWritten) "Stopped ($reason)." else STORAGE_FAILED_MESSAGE,
            )
        }
    }

    fun recordForegroundTimeout(fgsType: Int) {
        val session = active ?: return
        session.recorder.record(
            ProbeEventType.PROBE_ERROR,
            buildJsonObject {
                put("error_code", ProbeErrorCode.FGS_TIMEOUT.name)
                put("fgs_type_bits", fgsType)
            },
        )
    }

    /** Only app-specific storage is accepted; there is no public-directory or database fallback. */
    private fun prepareStorage(): File? = try {
        val dir = platform.evidenceBaseDir()
        if ((dir.isDirectory || dir.mkdirs()) && dir.isDirectory && dir.canWrite()) dir else null
    } catch (error: Exception) {
        null
    }

    private fun sampleNetworkTime(session: ActiveSession, reason: NetworkTimeSampleReason) {
        val reading = try {
            platform.networkTimeReading()
        } catch (error: Exception) {
            val wall = clock.wallMillis()
            val nanos = clock.elapsedRealtimeNanos()
            NetworkTimeReading(UNKNOWN_SDK, wall, nanos, nanos, null, error.javaClass.simpleName)
        }
        session.recorder.recordAt(
            ProbeEventType.NETWORK_TIME_SAMPLE,
            reading.wallMillis,
            reading.elapsedNanosBefore,
            NetworkTimeEvidence.fields(reading, reason),
        )
    }

    private suspend fun heartbeatLoop(session: ActiveSession) {
        val expected = HeartbeatEvaluator.INTERVAL.toNanos()
        var last = clock.elapsedRealtimeNanos()
        var tick = 0L
        while (currentCoroutineContext().isActive) {
            delay(HeartbeatEvaluator.INTERVAL.toMillis())
            val now = clock.elapsedRealtimeNanos()
            val observation = HeartbeatEvaluator.evaluate(expected, now - last)
            val fields = buildJsonObject {
                put("expected_interval_nanos", observation.expectedNanos)
                put("actual_interval_nanos", observation.actualNanos)
                put("late_by_nanos", observation.lateByNanos)
            }
            if (observation.isGap) {
                session.recorder.record(ProbeEventType.HEARTBEAT_GAP, fields)
                val gap = session.tracker.onHeartbeatGap(last, now)
                session.recorder.record(
                    ProbeEventType.STREAM_POSSIBLE_GAP,
                    buildJsonObject {
                        put("classification", gap.classification)
                        put("cause", gap.cause)
                        put("start_elapsed_realtime_nanos", gap.startElapsedNanos)
                        put("end_elapsed_realtime_nanos", gap.endElapsedNanos)
                        put("duration_nanos", gap.durationNanos)
                    },
                )
            } else {
                session.recorder.record(ProbeEventType.HEARTBEAT, fields)
            }
            last = now
            tick++
            if (tick % SAMPLE_EVERY_TICKS == 0L) {
                session.recorder.record(ProbeEventType.BATTERY_SAMPLE, platform.batterySample())
                session.recorder.record(ProbeEventType.THERMAL_SAMPLE, platform.thermalSample())
                session.recorder.record(ProbeEventType.TRAFFIC_SAMPLE, platform.trafficSample())
                sampleNetworkTime(session, NetworkTimeSampleReason.PERIODIC)
            }
            publishCounters(session)
        }
    }

    private suspend fun networkLoop(session: ActiveSession, pass: VirtualGatePass, options: ProbeStartOptions, scope: CoroutineScope) {
        session.networkJob = currentCoroutineContext().job
        val network = networkFactory(pass)
        val credentials = credentialSource.loadVirtualCredentials() ?: run {
            recordError(session, ProbeErrorCode.VIRTUAL_CREDENTIAL_MISSING)
            return
        }
        session.scrubber.register(credentials.appKey)
        session.scrubber.register(credentials.appSecret)

        val approvalKey = withRetry(session, "APPROVAL", ProbeErrorCode.WS_APPROVAL_FAILED) {
            network.requestApprovalKey(credentials)
        } ?: return
        session.scrubber.register(approvalKey.reveal())

        val stream = ProbeStreamSession(
            network = network,
            allowlist = ProbeWebSocketAllowlist(pass.endpoints),
            approvalKey = approvalKey,
            recorder = session.recorder,
            scrub = session.scrubber::scrub,
            clock = clock,
            scope = scope,
            tracker = session.tracker,
            onStatus = { status ->
                state.update {
                    if (it.status == ProbeStatus.ERROR || it.status == ProbeStatus.STOPPING) it else it.copy(status = status)
                }
            },
            onTerminalError = { code ->
                markError(session.errors, code)
                if (code == ProbeErrorCode.ALLOWLIST_DENIED) onPolicyDenied(session, "WEBSOCKET")
            },
        )
        session.stream = stream
        stream.start()

        if (!options.restMinuteBarObservation || stream.isTerminated || session.policyDenied.get()) return
        val token = credentialSource.loadReusableVirtualToken()?.also {
            session.recorder.record(ProbeEventType.REST_REQUEST_END, buildJsonObject { put("route", "TOKEN"); put("reused_stored_token", true) })
        } ?: withRetry(session, "TOKEN", ProbeErrorCode.VIRTUAL_TOKEN_FAILED) { network.issueToken(credentials) } ?: return
        session.scrubber.register(token.reveal())
        minuteBarLoop(session, network, credentials, token)
    }

    private suspend fun minuteBarLoop(session: ActiveSession, network: ProbeNetwork, credentials: KisCredentials, token: ProbeSecret) {
        while (currentCoroutineContext().isActive) {
            val now = clock.wallMillis()
            val nextMinute = (now / 60_000L + 1) * 60_000L + MINUTE_BAR_OFFSET_MILLIS
            delay(nextMinute - now)
            for (symbol in ProbeScope.SYMBOLS) {
                currentCoroutineContext().ensureActive()
                if (session.policyDenied.get()) return
                val hour = HHMMSS.format(Instant.ofEpochMilli(clock.wallMillis()).atZone(StrictAfterEvidenceClassifier.KST))
                session.recorder.record(
                    ProbeEventType.REST_REQUEST_START,
                    buildJsonObject {
                        put("route", "MINUTE_BARS")
                        put("tr_id", ProbeScope.MINUTE_BAR_TR_ID)
                        put("symbol", symbol)
                        put("input_hour_1", hour)
                    },
                )
                val startNanos = clock.elapsedRealtimeNanos()
                try {
                    val result = network.fetchTodayMinuteBars(token, credentials, symbol, hour)
                    session.recorder.record(
                        ProbeEventType.REST_REQUEST_END,
                        buildJsonObject {
                            put("route", "MINUTE_BARS")
                            put("symbol", symbol)
                            put("latency_nanos", clock.elapsedRealtimeNanos() - startNanos)
                            put("http_status", result.httpStatus)
                            result.rtCd?.let { put("rt_cd", it) }
                            result.msgCd?.let { put("msg_cd", it) }
                            result.msg1?.let { put("msg1", it) }
                            put("rows", result.rowCount)
                            result.firstRowBusinessDate?.let { put("first_row_stck_bsop_date", it) }
                            result.firstRowTradeHour?.let { put("first_row_stck_cntg_hour", it) }
                            result.lastRowTradeHour?.let { put("last_row_stck_cntg_hour", it) }
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ProbeAllowlistDeniedException) {
                    session.recorder.record(ProbeEventType.REST_FAILURE, failureFields("MINUTE_BARS", error, ProbeErrorCode.REST_FAILED))
                    recordError(session, ProbeErrorCode.ALLOWLIST_DENIED)
                    onPolicyDenied(session, "REST_MINUTE_BARS")
                    return
                } catch (error: Exception) {
                    session.recorder.record(ProbeEventType.REST_FAILURE, failureFields("MINUTE_BARS", error, ProbeErrorCode.REST_FAILED))
                }
                delay(REST_SPACING_MILLIS)
            }
        }
    }

    /** Retries only network-level failures; an HTTP rejection or allowlist denial ends the attempt with no fallback. */
    private suspend fun withRetry(
        session: ActiveSession,
        route: String,
        failure: ProbeErrorCode,
        block: suspend () -> ProbeSecret,
    ): ProbeSecret? {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (session.policyDenied.get()) return null
            attempt++
            session.recorder.record(ProbeEventType.REST_REQUEST_START, buildJsonObject { put("route", route); put("attempt", attempt) })
            val startNanos = clock.elapsedRealtimeNanos()
            try {
                val secret = block()
                session.recorder.record(
                    ProbeEventType.REST_REQUEST_END,
                    buildJsonObject {
                        put("route", route)
                        put("attempt", attempt)
                        put("latency_nanos", clock.elapsedRealtimeNanos() - startNanos)
                        put("result", "OK")
                    },
                )
                return secret
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session.recorder.record(ProbeEventType.REST_FAILURE, failureFields(route, error, failure))
                val retryable = error is IOException && error !is ProbeAllowlistDeniedException && error !is ProbeTransportException
                if (!retryable) {
                    recordError(session, if (error is ProbeAllowlistDeniedException) ProbeErrorCode.ALLOWLIST_DENIED else failure)
                    if (error is ProbeAllowlistDeniedException) onPolicyDenied(session, "REST_$route")
                    return null
                }
                state.update { it.copy(lastMessage = "${ProbeErrorCode.NETWORK_UNAVAILABLE.safeMessage} Retrying $route.") }
                delay(RETRY_DELAYS_SECONDS[minOf(attempt - 1, RETRY_DELAYS_SECONDS.lastIndex)] * 1000)
            }
        }
        return null
    }

    /**
     * The single terminal-policy signal for a session: once set, no new probe request (REST or WebSocket) begins.
     * Cancelling the network job cannot interrupt a blocking OkHttp call already in flight; its result is
     * discarded when the cancelled coroutine resumes, and nothing further is scheduled.
     */
    private fun onPolicyDenied(session: ActiveSession, source: String) {
        if (!session.policyDenied.compareAndSet(false, true)) return
        session.policySource = source
        val networkJob = session.networkJob
        session.recorder.record(
            ProbeEventType.NETWORK_POLICY_QUIESCED,
            buildJsonObject {
                put("error_code", ProbeErrorCode.ALLOWLIST_DENIED.name)
                put("source", source)
                put("network_job_was_active", networkJob?.isActive == true)
                put("terminal", true)
            },
        )
        networkJob?.cancel()
        session.stream?.stop()
    }

    /** Only class names and probe-defined codes are recorded; exception messages never are. */
    private fun failureFields(route: String, error: Exception, failure: ProbeErrorCode): JsonObject = buildJsonObject {
        put("route", route)
        put("error_class", error.javaClass.simpleName)
        when (error) {
            is ProbeAllowlistDeniedException -> {
                put("error_code", ProbeErrorCode.ALLOWLIST_DENIED.name)
                put("reason", error.reason)
            }
            is ProbeTransportException -> {
                put("error_code", error.code.name)
                error.httpStatus?.let { put("http_status", it) }
            }
            is IOException -> put("error_code", ProbeErrorCode.NETWORK_UNAVAILABLE.name)
            else -> put("error_code", failure.name)
        }
    }

    private fun recordError(session: ActiveSession, code: ProbeErrorCode, updateStatus: Boolean = true) {
        session.recorder.record(ProbeEventType.PROBE_ERROR, buildJsonObject { put("error_code", code.name) })
        if (updateStatus) {
            markError(session.errors, code)
        } else {
            session.errors += code.name
            state.update { it.copy(lastMessage = "${code.name}: ${code.safeMessage}") }
        }
    }

    private fun markError(errors: MutableList<String>, code: ProbeErrorCode) {
        errors += code.name
        state.update { it.copy(status = ProbeStatus.ERROR, lastMessage = "${code.name}: ${code.safeMessage} Press Stop to end the session.") }
    }

    private fun publishCounters(session: ActiveSession) {
        val stream = session.stream
        state.update {
            it.copy(
                records = stream?.recordCount?.get() ?: 0,
                frames = stream?.frameCount?.get() ?: 0,
                frameIssues = stream?.frameIssueCount?.get() ?: 0,
                reconnects = stream?.reconnectCount?.get() ?: 0,
                heartbeatGaps = session.recorder.count(ProbeEventType.HEARTBEAT_GAP),
                possibleGaps = session.tracker.possibleGapCount,
            )
        }
    }

    private fun buildMeta(
        sessionId: String,
        startWall: Long,
        startNanos: Long,
        options: ProbeStartOptions,
        fgsType: String,
        battery: JsonObject,
        traffic: JsonObject,
        thermal: JsonObject,
    ): JsonObject = buildJsonObject {
        put("evidence_schema_version", 3)
        put("session_id", sessionId)
        put("gate", "Phase 12-B2")
        put("app", appInfo)
        put(
            "build_provenance",
            buildJsonObject {
                put("source_commit", SOURCE_COMMIT_BINDING)
                put("binding_rule", "Run-gate evidence bundle records the git HEAD used to build the APK, the APK SHA-256, versionName/versionCode and the installed package version.")
            },
        )
        put("device", platform.deviceMetadata())
        put("probe_start_wall_time_epoch_ms", startWall)
        put("probe_start_elapsed_realtime_nanos", startNanos)
        put("environment", ProbeScope.ENVIRONMENT.name)
        put("venue", ProbeScope.VENUE_CODE)
        put("symbols", JsonArray(ProbeScope.SYMBOLS.map { JsonPrimitive(it) }))
        put("stream_tr_id", ProbeScope.STREAM_TR_ID)
        put("expected_stream_width", H0stcnt0Columns.WIDTH)
        put("candidate_fgs_type", ProbeScope.CANDIDATE_FGS_TYPE)
        put("started_fgs_type", fgsType)
        put("rest_minute_bar_observation", options.restMinuteBarObservation)
        put("initial_state", platform.stateSnapshot())
        put("initial_battery", battery)
        put("initial_traffic", traffic)
        put("initial_thermal", thermal)
        put("retention", "Retain until Human 12-B2 review; manual cleanup only")
    }

    private fun buildSummary(
        session: ActiveSession,
        reason: String,
        endWall: Long,
        endNanos: Long,
        endBattery: JsonObject,
        endTraffic: JsonObject,
        endThermal: JsonObject,
    ): JsonObject = buildJsonObject {
        val stream = session.stream
        val persisted = session.recorder.countsSnapshot()
        put("session_id", session.sessionId)
        put("stop_reason", reason)
        put("start_wall_time_epoch_ms", session.startWall)
        put("end_wall_time_epoch_ms", endWall)
        put("start_elapsed_realtime_nanos", session.startNanos)
        put("end_elapsed_realtime_nanos", endNanos)
        put("elapsed_realtime_duration_nanos", endNanos - session.startNanos)
        put("event_counts", buildJsonObject { persisted.forEach { (k, v) -> put(k.name, v) } })
        put("persisted_event_total", persisted.values.sum())
        put("events_rejected_after_close", session.recorder.rejectedAfterClose.get())
        put("events_not_persisted", session.recorder.notPersisted.get())
        put("ws_frames", stream?.frameCount?.get() ?: 0)
        put("ws_records", stream?.recordCount?.get() ?: 0)
        put("ws_frame_issues", stream?.frameIssueCount?.get() ?: 0)
        put("ws_reconnects", stream?.reconnectCount?.get() ?: 0)
        put("ws_terminated_by_policy", stream?.isTerminated ?: false)
        put("terminal_policy_denial", session.policyDenied.get())
        session.policySource?.let { put("terminal_policy_source", it) }
        put("possible_gap_windows", session.tracker.possibleGapCount)
        put("possible_gap_classification", StreamContinuityTracker.POSSIBLE_GAP)
        put("storage_failed", session.recorder.storageFailed)
        put("secret_scrub_replacements", session.scrubber.replacements)
        put("errors", JsonArray(session.errors.distinct().map { JsonPrimitive(it) }))
        put("start_battery", session.startBattery)
        put("end_battery", endBattery)
        put("start_traffic", session.startTraffic)
        put("end_traffic", endTraffic)
        put("start_thermal", session.startThermal)
        put("end_thermal", endThermal)
        put("network_time_scope", NetworkTimeEvidence.SCOPE)
        put("option_a_status", "NOT EVALUATED BY PROBE — POSSIBLY FEASIBLE, NEEDS PHYSICAL PROBE REVIEW")
        put("android_local_status", "NOT EVALUATED BY PROBE — POSSIBLY FEASIBLE, NEEDS PHYSICAL PROBE REVIEW")
    }

    companion object {
        const val SOURCE_COMMIT_BINDING = "externally_bound_at_run_gate"
        private const val UNKNOWN_SDK = -1
        private const val SAMPLE_EVERY_TICKS = 10L
        private const val MINUTE_BAR_OFFSET_MILLIS = 20_000L
        private const val REST_SPACING_MILLIS = 600L
        private val RETRY_DELAYS_SECONDS = listOf(5L, 15L, 30L, 60L)
        private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
        private val STORAGE_FAILED_MESSAGE = "${ProbeErrorCode.PROBE_STORAGE_FAILED.name}: ${ProbeErrorCode.PROBE_STORAGE_FAILED.safeMessage}"
    }
}
