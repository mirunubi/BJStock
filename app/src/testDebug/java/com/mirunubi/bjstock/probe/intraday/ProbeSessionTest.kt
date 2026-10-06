package com.mirunubi.bjstock.probe.intraday

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun events(store: ProbeEvidenceStore, sessionId: String): List<JsonObject> =
        store.files(sessionId).events.readLines().map { Json.parseToJsonElement(it).jsonObject }

    private fun List<JsonObject>.ofType(type: ProbeEventType) = filter { it["event_type"]!!.jsonPrimitive.content == type.name }

    private class StreamHarness(root: java.io.File, symbols: List<String> = ProbeScope.SYMBOLS) {
        val sessionId = ProbeSessionId.create(1_790_000_000_000L)
        val store = ProbeEvidenceStore(root, SecretScrubber())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock()
        val recorder = ProbeRecorder(sessionId, store, clock) { fail("storage failed") }.also { it.start(scope) }
        val network = FakeNetwork()
        val statuses = CopyOnWriteArrayList<ProbeStatus>()
        val terminalErrors = CopyOnWriteArrayList<ProbeErrorCode>()
        val session = ProbeStreamSession(
            network = network,
            allowlist = ProbeWebSocketAllowlist(ProbeEndpoints.KIS_VIRTUAL),
            approvalKey = ProbeSecret(ProbeFixtures.FAKE_APPROVAL_KEY),
            recorder = recorder,
            scrub = { it },
            clock = clock,
            scope = scope,
            tracker = StreamContinuityTracker(),
            onStatus = { statuses += it },
            onTerminalError = { terminalErrors += it },
            symbols = symbols,
            backoffSeconds = listOf(0),
        )

        suspend fun finish() {
            session.stopAndJoin()
            recorder.close()
            scope.cancel()
        }
    }

    private fun recordFlags(records: List<JsonObject>) =
        records.map { it["symbol"]!!.jsonPrimitive.content to it["first_after_reconnect"]!!.jsonPrimitive.content }

    @Test
    fun streamSubscribesApprovedPairsAndNeverRecordsTheApprovalKey() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        h.network.callbacks.single().onOpen()

        val sent = h.network.sockets.single().sent
        assertEquals(2, sent.size)
        val inputs = sent.map { Json.parseToJsonElement(it).jsonObject["body"]!!.jsonObject["input"]!!.jsonObject }
        assertEquals(listOf("H0STCNT0", "H0STCNT0"), inputs.map { it["tr_id"]!!.jsonPrimitive.content })
        assertEquals(listOf("005930", "000660"), inputs.map { it["tr_key"]!!.jsonPrimitive.content })
        assertTrue(sent.all { it.contains(ProbeFixtures.FAKE_APPROVAL_KEY) })

        // Same-second, multi-record frame: provider HHMMSS is kept raw, no milliseconds are manufactured.
        h.clock.wall = java.time.Instant.parse("2026-10-02T00:30:01.250Z").toEpochMilli()
        h.network.callbacks.single().onText(
            ProbeFixtures.frame(ProbeFixtures.record("005930", "093001", "70000"), ProbeFixtures.record("000660", "093001", "180000")),
        )
        h.network.callbacks.single().onText("""{"header":{"tr_id":"PINGPONG","datetime":"20261002093001"}}""")
        assertEquals(3, sent.size)

        // Connection loss -> reconnect -> possible gap, never a backfill.
        h.network.callbacks.single().onFailure(IOException("reset"))
        awaitUntil { h.network.callbacks.size == 2 }
        h.network.callbacks[1].onOpen()
        awaitUntil { h.network.sockets.size == 2 && h.network.sockets[1].sent.size == 2 }
        h.finish()

        val all = events(h.store, h.sessionId)
        assertEquals(4, all.ofType(ProbeEventType.WS_SUBSCRIBE_SENT).size)
        val records = all.ofType(ProbeEventType.WS_RECORD)
        assertEquals(2, records.size)
        assertEquals(listOf("093001", "093001"), records.map { it["provider_trade_time_hhmmss"]!!.jsonPrimitive.content })
        assertEquals(listOf("0", "1"), records.map { it["record_index"]!!.jsonPrimitive.content })
        assertEquals(setOf("1"), records.map { it["frame_seq"]!!.jsonPrimitive.content }.toSet())
        assertEquals(listOf("250", "250"), records.map { it["derived_receipt_wall_minus_provider_second_start_ms"]!!.jsonPrimitive.content })
        assertTrue(records.none { it.keys.any { key -> key.contains("millis") && key.startsWith("provider") } })
        val gap = all.ofType(ProbeEventType.STREAM_POSSIBLE_GAP).single()
        assertEquals("UNPROVEN_POSSIBLE_GAP", gap["classification"]!!.jsonPrimitive.content)
        assertEquals(1, all.ofType(ProbeEventType.WS_RECONNECT_SCHEDULED).size)
        val ping = all.ofType(ProbeEventType.WS_FRAME).single { it["frame_kind"]!!.jsonPrimitive.content == "PINGPONG" }
        assertEquals("true", ping["pingpong_reply_attempted"]!!.jsonPrimitive.content)
        assertEquals("true", ping["pingpong_text_echo_sent"]!!.jsonPrimitive.content)
        val failure = all.ofType(ProbeEventType.WS_FAILURE).single()
        assertEquals("IOException", failure["error_class"]!!.jsonPrimitive.content)
        assertEquals("1", failure["pingpong_count_this_connection"]!!.jsonPrimitive.content)
        assertTrue(failure.containsKey("connection_duration_nanos"))
        assertTrue(failure.containsKey("since_last_pingpong_nanos"))
        // WS-CONNECT-02: the bounded message is recorded; this fake failure carried no response.
        assertEquals("reset", failure["error_message"]!!.jsonPrimitive.content)
        assertEquals("false", failure["response_present"]!!.jsonPrimitive.content)
        assertFalse(failure.containsKey("response_code"))
        assertEquals("1", failure["attempt"]!!.jsonPrimitive.content)

        val text = ProbeFixtures.readAll(temp.root)
        ProbeFixtures.ALL_SECRETS.forEach { assertFalse("leaked $it", text.contains(it)) }
        assertTrue(h.statuses.containsAll(listOf(ProbeStatus.CONNECTED, ProbeStatus.RUNNING, ProbeStatus.RECONNECTING)))
        assertTrue(h.terminalErrors.isEmpty())
    }

    // F-7
    @Test
    fun firstAfterReconnectIsMarkedOncePerSymbol() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        h.network.callbacks.single().onOpen()
        h.network.callbacks.single().onText(ProbeFixtures.frame(ProbeFixtures.record("005930", "093000", "70000")))
        h.network.callbacks.single().onFailure(IOException("reset"))
        awaitUntil { h.network.callbacks.size == 2 }
        val second = h.network.callbacks[1]
        second.onOpen()
        second.onText(ProbeFixtures.frame(ProbeFixtures.record("005930", "093010", "70100")))
        second.onText(ProbeFixtures.frame(ProbeFixtures.record("005930", "093011", "70100"), ProbeFixtures.record("000660", "093011", "180000")))
        second.onText(ProbeFixtures.frame(ProbeFixtures.record("000660", "093012", "180100")))
        h.finish()

        val records = events(h.store, h.sessionId).ofType(ProbeEventType.WS_RECORD)
        assertEquals(
            listOf(
                "005930" to "false",
                "005930" to "true",
                "005930" to "false",
                "000660" to "true",
                "000660" to "false",
            ),
            recordFlags(records),
        )
    }

    // F-4
    @Test
    fun endpointAllowlistDenialIsTerminalWithoutReconnect() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        h.network.callbacks.single().onFailure(IOException("wrapped", ProbeAllowlistDeniedException("HOST_NOT_ALLOWED")))
        delay(300)
        h.network.callbacks.single().onFailure(ProbeAllowlistDeniedException("HOST_NOT_ALLOWED"))
        h.finish()

        assertEquals(1, h.network.callbacks.size)
        assertEquals(0L, h.session.reconnectCount.get())
        assertTrue(h.session.isTerminated)
        assertEquals(listOf(ProbeErrorCode.ALLOWLIST_DENIED), h.terminalErrors.toList())
        val all = events(h.store, h.sessionId)
        assertTrue(all.ofType(ProbeEventType.WS_RECONNECT_SCHEDULED).isEmpty())
        assertTrue(all.ofType(ProbeEventType.WS_RECONNECT_ATTEMPT).isEmpty())
        val error = all.ofType(ProbeEventType.PROBE_ERROR).single()
        assertEquals("ALLOWLIST_DENIED", error["error_code"]!!.jsonPrimitive.content)
        assertEquals("ENDPOINT", error["stage"]!!.jsonPrimitive.content)
        assertEquals("HOST_NOT_ALLOWED", error["reason"]!!.jsonPrimitive.content)
        assertFalse(all.toString().contains("wrapped"))
    }

    @Test
    fun subscriptionAllowlistDenialSendsNothingAndIsTerminal() = runBlocking {
        val h = StreamHarness(temp.root, symbols = listOf("005930", "000660", "035420"))
        h.session.start()
        h.network.callbacks.single().onOpen()
        delay(300)
        h.finish()

        assertTrue(h.network.sockets.single().sent.isEmpty())
        assertTrue(h.network.sockets.single().closed)
        assertEquals(1, h.network.callbacks.size)
        assertEquals(listOf(ProbeErrorCode.ALLOWLIST_DENIED), h.terminalErrors.toList())
        val all = events(h.store, h.sessionId)
        assertTrue(all.ofType(ProbeEventType.WS_SUBSCRIBE_SENT).isEmpty())
        assertTrue(all.ofType(ProbeEventType.WS_RECONNECT_SCHEDULED).isEmpty())
        val error = all.ofType(ProbeEventType.PROBE_ERROR).single()
        assertEquals("SUBSCRIPTION", error["stage"]!!.jsonPrimitive.content)
        assertEquals("SYMBOL_NOT_ALLOWED", error["reason"]!!.jsonPrimitive.content)
    }

    // F-5
    @Test
    fun lateCallbacksAfterStopProduceNoEventsAndNoReconnect() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        val first = h.network.callbacks.single()
        first.onOpen()
        h.session.stopAndJoin()
        val linesAtStop = run {
            h.recorder.close()
            events(h.store, h.sessionId).size
        }
        first.onText(ProbeFixtures.frame(ProbeFixtures.record("005930", "093000", "70000")))
        first.onClosed(1000)
        first.onFailure(IOException("late"))
        delay(300)
        h.scope.cancel()

        assertEquals(1, h.network.callbacks.size)
        assertTrue(h.network.sockets.single().closed)
        assertEquals(linesAtStop, events(h.store, h.sessionId).size)
        assertEquals(linesAtStop.toLong(), h.recorder.countsSnapshot().values.sum())
        val types = events(h.store, h.sessionId).map { it["event_type"]!!.jsonPrimitive.content }
        assertFalse(types.contains("WS_DISCONNECTED") || types.contains("WS_FAILURE") || types.contains("WS_RECORD"))
    }

    private fun controller(
        root: java.io.File,
        state: MutableStateFlow<ProbeUiState>,
        credentials: FakeCredentialSource = FakeCredentialSource(ProbeFixtures.CREDENTIALS),
        platform: FakePlatform = FakePlatform(root),
        networks: () -> FakeNetwork = { FakeNetwork() },
        factoryCalls: AtomicInteger = AtomicInteger(),
    ) = ProbeSessionController(
        credentialSource = credentials,
        platform = platform,
        clock = FakeClock(),
        appInfo = buildJsonObject { put("version_name", "test") },
        networkFactory = { pass ->
            assertEquals(ProbeEndpoints.KIS_VIRTUAL, pass.endpoints)
            factoryCalls.incrementAndGet()
            networks()
        },
        state = state,
    )

    private fun summary(root: java.io.File, sessionId: String): JsonObject =
        Json.parseToJsonElement(ProbeEvidenceStore(root, SecretScrubber()).files(sessionId).summary.readText()).jsonObject

    @Test
    fun missingVirtualCredentialsStaysStoppedAndNeverBuildsATransport() = runBlocking {
        val state = MutableStateFlow(ProbeUiState())
        val factoryCalls = AtomicInteger()
        val controller = controller(temp.root, state, credentials = FakeCredentialSource(null), factoryCalls = factoryCalls)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outcome = controller.start(scope, ProbeStartOptions(), "specialUse")
        controller.stop("SERVICE_DESTROYED")
        scope.cancel()

        assertEquals(ProbeStartOutcome.REFUSED_GATE, outcome)
        assertFalse(controller.isActive)
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        assertTrue(state.value.lastMessage.contains("VIRTUAL quotation credentials are not configured."))
        assertEquals(0, factoryCalls.get())
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    @Test
    fun controllerWritesIsolatedSessionEvidenceAndStopsCleanly() = runBlocking {
        val state = MutableStateFlow(ProbeUiState())
        val network = FakeNetwork()
        val platform = FakePlatform(temp.root, networkTimeMillis = 1_789_999_999_900L)
        val controller = controller(temp.root, state, platform = platform, networks = { network })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertEquals(ProbeStartOutcome.STARTED, controller.start(scope, ProbeStartOptions(), "specialUse"))
        awaitUntil { network.callbacks.isNotEmpty() }
        network.callbacks.single().onOpen()
        awaitUntil { network.sockets.firstOrNull()?.sent?.size == 2 }
        assertEquals(1, platform.observing.get())
        platform.clockChanged!!.invoke(NetworkTimeSampleReason.TIME_CHANGED)
        platform.clockChanged!!.invoke(NetworkTimeSampleReason.TIMEZONE_CHANGED)
        val sessionId = state.value.sessionId!!
        assertTrue(sessionId.matches(ProbeSessionId.PATTERN))
        assertTrue(state.value.sessionActive)

        controller.stop("TEST_STOP")
        network.callbacks.single().onClosed(1000)
        network.callbacks.single().onText(ProbeFixtures.frame(ProbeFixtures.record("005930", "093000", "70000")))
        delay(200)
        scope.cancel()

        assertEquals(ProbeStatus.STOPPED, state.value.status)
        assertFalse(state.value.sessionActive)
        assertEquals(0, platform.observing.get())
        assertEquals(0, network.tokenCalls.get())
        assertEquals(1, network.approvalCalls.get())
        assertTrue(network.sockets.single().closed)
        val store = ProbeEvidenceStore(temp.root, SecretScrubber())
        val files = store.files(sessionId)
        assertTrue(files.meta.isFile && files.events.isFile && files.summary.isFile)
        val meta = Json.parseToJsonElement(files.meta.readText()).jsonObject
        assertEquals("VIRTUAL", meta["environment"]!!.jsonPrimitive.content)
        assertEquals("J", meta["venue"]!!.jsonPrimitive.content)
        assertEquals("specialUse", meta["candidate_fgs_type"]!!.jsonPrimitive.content)
        assertEquals("externally_bound_at_run_gate", meta["build_provenance"]!!.jsonObject["source_commit"]!!.jsonPrimitive.content)

        val all = events(store, sessionId)
        val types = all.map { it["event_type"]!!.jsonPrimitive.content }
        assertEquals("SESSION_START", types.first())
        assertEquals("SESSION_STOP", types.last())
        assertTrue(types.containsAll(listOf("FGS_START", "WS_CONNECTING", "WS_CONNECTED", "WS_SUBSCRIBE_SENT", "FGS_STOP")))
        assertFalse(types.contains("WS_DISCONNECTED"))
        assertFalse(types.contains("WS_RECORD"))
        val samples = all.ofType(ProbeEventType.NETWORK_TIME_SAMPLE)
        assertEquals(
            listOf("SESSION_START", "TIME_CHANGED", "TIMEZONE_CHANGED", "SESSION_STOP"),
            samples.map { it["sample_reason"]!!.jsonPrimitive.content },
        )
        assertTrue(samples.all { it["network_time_available"]!!.jsonPrimitive.content == "true" })
        assertTrue(samples.all { it.containsKey("wall_minus_network_ms") })

        // F-5: summary counts equal the persisted JSONL lines, per type and in total.
        val summary = summary(temp.root, sessionId)
        assertEquals("TEST_STOP", summary["stop_reason"]!!.jsonPrimitive.content)
        assertEquals(all.size.toLong(), summary["persisted_event_total"]!!.jsonPrimitive.long)
        val counted = summary["event_counts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }
        assertEquals(all.groupingBy { it["event_type"]!!.jsonPrimitive.content }.eachCount().mapValues { it.value.toLong() }, counted)

        val text = ProbeFixtures.readAll(temp.root)
        ProbeFixtures.ALL_SECRETS.forEach { assertFalse("leaked $it", text.contains(it)) }
    }

    // F-1
    @Test
    fun errorSessionRequiresStopThenAllowsANewStart() = runBlocking {
        val state = MutableStateFlow(ProbeUiState())
        val platform = FakePlatform(temp.root)
        val failing = FakeNetwork(approvalFailure = ProbeTransportException(ProbeErrorCode.WS_APPROVAL_FAILED, 401))
        val healthy = FakeNetwork()
        val queue = ArrayDeque(listOf(failing, healthy))
        val controller = controller(temp.root, state, platform = platform, networks = { queue.removeFirst() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        assertEquals(ProbeStartOutcome.STARTED, controller.start(scope, ProbeStartOptions(), "specialUse"))
        awaitUntil { state.value.status == ProbeStatus.ERROR }
        val erroredSession = state.value.sessionId!!
        assertTrue(state.value.sessionActive)
        assertTrue(state.value.canStop)
        assertFalse(state.value.canStart)
        assertTrue(state.value.lastMessage.startsWith("WS_APPROVAL_FAILED"))
        assertFalse(state.value.lastMessage.contains("401"))

        assertEquals(ProbeStartOutcome.REFUSED_ACTIVE_SESSION, controller.start(scope, ProbeStartOptions(), "specialUse"))
        assertTrue(state.value.lastMessage.contains("Stop it before starting a new one."))
        assertEquals(erroredSession, state.value.sessionId)
        assertEquals(ProbeStatus.ERROR, state.value.status)

        controller.stop("USER_STOP")
        controller.stop("SERVICE_DESTROYED")
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        assertEquals("Stopped (USER_STOP).", state.value.lastMessage)
        assertFalse(state.value.sessionActive)
        assertEquals(0, platform.observing.get())
        val linesAfterStop = events(ProbeEvidenceStore(temp.root, SecretScrubber()), erroredSession).size
        delay(300)
        assertEquals(linesAfterStop, events(ProbeEvidenceStore(temp.root, SecretScrubber()), erroredSession).size)
        val errorSummary = summary(temp.root, erroredSession)
        assertTrue(errorSummary["errors"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("WS_APPROVAL_FAILED"))
        assertEquals(linesAfterStop.toLong(), errorSummary["persisted_event_total"]!!.jsonPrimitive.long)

        assertEquals(ProbeStartOutcome.STARTED, controller.start(scope, ProbeStartOptions(), "specialUse"))
        awaitUntil { healthy.callbacks.isNotEmpty() }
        assertTrue(state.value.sessionId != erroredSession)
        controller.stop("USER_STOP")
        scope.cancel()
        assertEquals(ProbeStatus.STOPPED, state.value.status)
    }

    // F-4 through the controller: terminal ERROR, no reconnect, still stoppable.
    @Test
    fun webSocketDenialMakesSessionErrorButStoppable() = runBlocking {
        val state = MutableStateFlow(ProbeUiState())
        val network = FakeNetwork()
        val controller = controller(temp.root, state, networks = { network })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller.start(scope, ProbeStartOptions(), "specialUse")
        awaitUntil { network.callbacks.isNotEmpty() }
        network.callbacks.single().onFailure(ProbeAllowlistDeniedException("PORT_NOT_ALLOWED"))
        awaitUntil { state.value.status == ProbeStatus.ERROR }
        delay(300)
        assertEquals(1, network.callbacks.size)
        assertTrue(state.value.canStop)
        val sessionId = state.value.sessionId!!

        controller.stop("USER_STOP")
        scope.cancel()
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        val summary = summary(temp.root, sessionId)
        assertEquals("true", summary["ws_terminated_by_policy"]!!.jsonPrimitive.content)
        assertEquals(0L, summary["ws_reconnects"]!!.jsonPrimitive.long)
        assertTrue(summary["errors"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("ALLOWLIST_DENIED"))
    }

    // F-8
    @Test
    fun unavailableStorageFailsSafelyBeforeCredentialsOrTransport() = runBlocking {
        listOf(
            FakePlatform(temp.root, storageFailure = ProbeStorageException("external app-specific storage unavailable")),
            FakePlatform(temp.newFile("not_a_directory")),
        ).forEach { platform ->
            val state = MutableStateFlow(ProbeUiState())
            val credentials = FakeCredentialSource(ProbeFixtures.CREDENTIALS)
            val factoryCalls = AtomicInteger()
            val controller = controller(temp.root, state, credentials = credentials, platform = platform, factoryCalls = factoryCalls)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            assertEquals(ProbeStartOutcome.REFUSED_STORAGE, controller.start(scope, ProbeStartOptions(), "specialUse"))
            assertEquals(ProbeStatus.ERROR, state.value.status)
            assertEquals("PROBE_STORAGE_FAILED: Probe evidence storage failed.", state.value.lastMessage)
            assertFalse(state.value.sessionActive)
            assertTrue(state.value.canStop)
            assertEquals(0, credentials.hasCalls.get())
            assertEquals(0, credentials.loadCalls.get())
            assertEquals(0, factoryCalls.get())
            assertEquals(0, platform.observing.get())

            // B2A-D-F01: service cleanup and a later Stop are no-ops that keep the refusal visible.
            controller.stop("SERVICE_DESTROYED")
            controller.stop("USER_STOP")
            scope.cancel()
            assertEquals(ProbeStatus.ERROR, state.value.status)
            assertEquals("PROBE_STORAGE_FAILED: Probe evidence storage failed.", state.value.lastMessage)
            assertFalse(state.value.sessionActive)
            assertFalse(controller.isActive)
            assertTrue(state.value.canStart)
            assertEquals(0, credentials.hasCalls.get())
            assertEquals(0, credentials.loadCalls.get())
            assertEquals(0, factoryCalls.get())
            assertEquals(0, platform.observing.get())
        }
    }

    // B2A-D-F01
    @Test
    fun noSessionStopWithoutPriorErrorIsBenign() = runBlocking {
        val state = MutableStateFlow(ProbeUiState())
        val credentials = FakeCredentialSource(ProbeFixtures.CREDENTIALS)
        val platform = FakePlatform(temp.root)
        val factoryCalls = AtomicInteger()
        val controller = controller(temp.root, state, credentials = credentials, platform = platform, factoryCalls = factoryCalls)

        controller.stop("USER_STOP")
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        assertEquals("Not started.", state.value.lastMessage)

        state.value = ProbeUiState(status = ProbeStatus.STARTING)
        controller.stop("SERVICE_DESTROYED")
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        assertEquals("No active session.", state.value.lastMessage)
        assertFalse(state.value.canStop)
        assertFalse(controller.isActive)
        assertEquals(0, credentials.hasCalls.get())
        assertEquals(0, credentials.loadCalls.get())
        assertEquals(0, factoryCalls.get())
        assertEquals(0, platform.observing.get())
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    private fun List<JsonObject>.indexOfType(type: ProbeEventType) = indexOfFirst { it["event_type"]!!.jsonPrimitive.content == type.name }

    private fun List<JsonObject>.minuteBarStartsAfter(index: Int) = drop(index + 1).ofType(ProbeEventType.REST_REQUEST_START)
        .filter { it["route"]!!.jsonPrimitive.content == "MINUTE_BARS" }

    // B2A-D-F02
    @Test
    fun webSocketPolicyDenialStopsMinuteBarObservationBeforeAnyNewRequest() = runTest {
        val state = MutableStateFlow(ProbeUiState())
        val network = FakeNetwork()
        val controller = controller(temp.root, state, networks = { network })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        assertEquals(ProbeStartOutcome.STARTED, controller.start(scope, ProbeStartOptions(restMinuteBarObservation = true), "specialUse"))
        runCurrent()
        assertEquals(1, network.tokenCalls.get())

        advanceTimeBy(65_000)
        runCurrent()
        val callsBeforeDenial = network.minuteBarCalls.get()
        assertEquals(2, callsBeforeDenial)

        network.callbacks.single().onFailure(ProbeAllowlistDeniedException("PORT_NOT_ALLOWED"))
        assertEquals(ProbeStatus.ERROR, state.value.status)
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(callsBeforeDenial, network.minuteBarCalls.get())
        assertEquals(1, network.callbacks.size)
        assertEquals(1, network.tokenCalls.get())
        assertEquals(ProbeStatus.ERROR, state.value.status)
        assertTrue(state.value.sessionActive)
        assertTrue(state.value.canStop)
        val sessionId = state.value.sessionId!!

        controller.stop("USER_STOP")
        scope.cancel()
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        assertFalse(state.value.sessionActive)

        val all = events(ProbeEvidenceStore(temp.root, SecretScrubber()), sessionId)
        val quiesced = all.ofType(ProbeEventType.NETWORK_POLICY_QUIESCED).single()
        assertEquals("WEBSOCKET", quiesced["source"]!!.jsonPrimitive.content)
        assertEquals("ALLOWLIST_DENIED", quiesced["error_code"]!!.jsonPrimitive.content)
        assertEquals("true", quiesced["network_job_was_active"]!!.jsonPrimitive.content)
        val denial = all.indexOfFirst {
            it["event_type"]!!.jsonPrimitive.content == "PROBE_ERROR" && it["error_code"]?.jsonPrimitive?.content == "ALLOWLIST_DENIED"
        }
        assertTrue(denial >= 0)
        assertTrue(all.minuteBarStartsAfter(denial).isEmpty())
        assertTrue(all.ofType(ProbeEventType.WS_RECONNECT_SCHEDULED).isEmpty())
        assertTrue(all.ofType(ProbeEventType.WS_RECONNECT_ATTEMPT).isEmpty())
        val summary = summary(temp.root, sessionId)
        assertEquals("true", summary["terminal_policy_denial"]!!.jsonPrimitive.content)
        assertEquals("WEBSOCKET", summary["terminal_policy_source"]!!.jsonPrimitive.content)
        assertEquals("true", summary["ws_terminated_by_policy"]!!.jsonPrimitive.content)
        assertEquals(0L, summary["ws_reconnects"]!!.jsonPrimitive.long)
        val text = ProbeFixtures.readAll(temp.root)
        ProbeFixtures.ALL_SECRETS.forEach { assertFalse("leaked $it", text.contains(it)) }
    }

    // B2A-D-F02: a request already in flight is not followed by another one.
    @Test
    fun policyDenialDuringInFlightMinuteBarRequestSchedulesNoFurtherRequest() = runTest {
        val state = MutableStateFlow(ProbeUiState())
        val network = FakeNetwork().apply { minuteBarGate = CompletableDeferred() }
        val controller = controller(temp.root, state, networks = { network })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        controller.start(scope, ProbeStartOptions(restMinuteBarObservation = true), "specialUse")
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, network.minuteBarCalls.get())

        network.callbacks.single().onFailure(ProbeAllowlistDeniedException("HOST_NOT_ALLOWED"))
        network.minuteBarGate!!.complete(Unit)
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(1, network.minuteBarCalls.get())
        assertEquals(ProbeStatus.ERROR, state.value.status)
        val sessionId = state.value.sessionId!!
        controller.stop("USER_STOP")
        scope.cancel()
        assertEquals(ProbeStatus.STOPPED, state.value.status)

        val all = events(ProbeEvidenceStore(temp.root, SecretScrubber()), sessionId)
        val minuteStarts = all.ofType(ProbeEventType.REST_REQUEST_START).filter { it["route"]!!.jsonPrimitive.content == "MINUTE_BARS" }
        assertEquals(listOf("005930"), minuteStarts.map { it["symbol"]!!.jsonPrimitive.content })
        assertTrue(all.ofType(ProbeEventType.REST_REQUEST_END).none { it["route"]?.jsonPrimitive?.content == "MINUTE_BARS" })
        assertTrue(all.minuteBarStartsAfter(all.indexOfType(ProbeEventType.NETWORK_POLICY_QUIESCED)).isEmpty())
    }

    // B2A-D-F02: a REST policy denial also ends the WebSocket; a later transport loss does not reconnect.
    @Test
    fun restPolicyDenialAlsoStopsTheWebSocketWithoutReconnect() = runTest {
        val state = MutableStateFlow(ProbeUiState())
        val network = FakeNetwork(minuteBarFailure = ProbeAllowlistDeniedException("PATH_NOT_ALLOWED"))
        val controller = controller(temp.root, state, networks = { network })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        controller.start(scope, ProbeStartOptions(restMinuteBarObservation = true), "specialUse")
        runCurrent()
        network.callbacks.single().onOpen()
        advanceTimeBy(65_000)
        runCurrent()

        assertEquals(1, network.minuteBarCalls.get())
        assertEquals(ProbeStatus.ERROR, state.value.status)
        assertTrue(network.sockets.single().closed)
        network.callbacks.single().onFailure(IOException("late reset"))
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, network.callbacks.size)
        assertEquals(1, network.minuteBarCalls.get())
        assertTrue(state.value.canStop)
        val sessionId = state.value.sessionId!!

        controller.stop("USER_STOP")
        scope.cancel()
        assertEquals(ProbeStatus.STOPPED, state.value.status)
        val all = events(ProbeEvidenceStore(temp.root, SecretScrubber()), sessionId)
        assertEquals("REST_MINUTE_BARS", all.ofType(ProbeEventType.NETWORK_POLICY_QUIESCED).single()["source"]!!.jsonPrimitive.content)
        assertTrue(all.ofType(ProbeEventType.WS_RECONNECT_SCHEDULED).isEmpty())
        assertFalse(all.toString().contains("late reset"))
        val summary = summary(temp.root, sessionId)
        assertEquals("REST_MINUTE_BARS", summary["terminal_policy_source"]!!.jsonPrimitive.content)
    }
}
