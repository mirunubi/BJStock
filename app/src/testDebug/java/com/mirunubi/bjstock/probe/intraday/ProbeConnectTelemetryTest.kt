package com.mirunubi.bjstock.probe.intraday

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WS-CONNECT-02: bounded, scrubbed connection-stage telemetry with no connection behaviour change. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeConnectTelemetryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val identity: (String) -> String = { it }

    private fun registeredScrubber() = SecretScrubber().apply { ProbeFixtures.ALL_SECRETS.forEach(::register) }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun events(store: ProbeEvidenceStore, sessionId: String): List<JsonObject> =
        store.files(sessionId).events.readLines().map { Json.parseToJsonElement(it).jsonObject }

    private fun List<JsonObject>.ofType(type: ProbeEventType) = filter { it.text("event_type") == type.name }

    private fun assertNoSecretsOrForbiddenKeys(text: String, objects: List<JsonObject>) {
        ProbeFixtures.ALL_SECRETS.forEach { secret ->
            assertFalse("leaked $secret", text.contains(secret))
            assertFalse("leaked a prefix of $secret", text.contains(secret.take(12)))
        }
        objects.flatMap { it.keys }.forEach { key -> assertFalse("forbidden key $key", ProbeRedaction.isForbiddenKey(key)) }
    }

    // ---------------------------------------------------------------- Throwable (§13)

    @Test
    fun plainUnexpectedEndOfStreamRecordsClassAndBoundedMessage() {
        val error = IOException(
            "unexpected end of stream on http://ops.koreainvestment.com:31000/...",
            EOFException("\\n not found: limit=0 content=…"),
        )
        val fields = ProbeThrowableTelemetry.fields(error, identity)

        assertEquals("IOException", fields.text("error_class"))
        assertEquals("unexpected end of stream on http://ops.koreainvestment.com:31000/...", fields.text("error_message"))
        assertEquals("EOFException", fields.text("cause_1_class"))
        assertEquals("\\n not found: limit=0 content=…", fields.text("cause_1_message"))
        assertEquals("0", fields.text("suppressed_count"))
        assertFalse(fields.containsKey("cause_chain_truncated"))
        assertTrue(fields.keys.none { it.contains("stack", ignoreCase = true) || it.contains("trace", ignoreCase = true) })
        assertFalse(fields.toString().contains("\tat "))
    }

    @Test
    fun nestedCauseChainRecordsEachClassInOrder() {
        val middle = SocketException("middle").apply { initCause(ConnectException("inner")) }
        val error = IOException("outer", middle)
        val fields = ProbeThrowableTelemetry.fields(error, identity)

        assertEquals("IOException", fields.text("error_class"))
        assertEquals("SocketException", fields.text("cause_1_class"))
        assertEquals("ConnectException", fields.text("cause_2_class"))
        assertEquals("inner", fields.text("cause_2_message"))
        assertFalse(fields.containsKey("cause_3_class"))
    }

    @Test
    fun causeChainDeeperThanTheLimitIsCutAtTheHardMaximum() {
        var error: Throwable = IOException("leaf")
        repeat(11) { error = IOException("level $it", error) }
        val fields = ProbeThrowableTelemetry.fields(error, identity)

        val recorded = (1..20).filter { fields.containsKey("cause_${it}_class") }
        assertEquals((1 until ProbeThrowableTelemetry.MAX_CHAIN_DEPTH).toList(), recorded)
        assertEquals("true", fields.text("cause_chain_truncated"))
    }

    @Test
    fun causeCycleStopsWithoutRepeatingOrClaimingTruncation() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        val fields = ProbeThrowableTelemetry.fields(a, identity)

        assertEquals("b", fields.text("cause_1_message"))
        assertFalse(fields.containsKey("cause_2_class"))
        assertFalse(fields.containsKey("cause_chain_truncated"))
    }

    @Test
    fun suppressedExceptionsAreCountedButOnlyTheCapIsDescribed() {
        val error = ConnectException("last route")
        repeat(5) { error.addSuppressed(IOException("route $it")) }
        val fields = ProbeThrowableTelemetry.fields(error, identity)

        assertEquals("5", fields.text("suppressed_count"))
        (1..ProbeThrowableTelemetry.MAX_SUPPRESSED).forEach {
            assertEquals("IOException", fields.text("suppressed_${it}_class"))
            assertEquals("route ${it - 1}", fields.text("suppressed_${it}_message"))
        }
        assertFalse(fields.containsKey("suppressed_${ProbeThrowableTelemetry.MAX_SUPPRESSED + 1}_class"))
    }

    @Test
    fun longMessagesAreTruncatedToTheBound() {
        val fields = ProbeThrowableTelemetry.fields(IOException("x".repeat(1_000)), identity)
        val message = fields.text("error_message")!!

        assertEquals(ProbeThrowableTelemetry.MAX_MESSAGE_LENGTH, message.length)
        assertTrue(message.endsWith("…"))
        assertEquals("short", ProbeThrowableTelemetry.message(IOException("short"), identity))
        assertNull(ProbeThrowableTelemetry.message(IOException(), identity))
    }

    @Test
    fun secretsAreScrubbedBeforeTruncationEverywhereInTheThrowable() {
        val scrubber = registeredScrubber()
        // The approval key straddles the 160-character cut: truncating first would leave a raw prefix.
        val straddling = "x".repeat(150) + ProbeFixtures.FAKE_APPROVAL_KEY
        val error = IOException(straddling, IOException("cause " + ProbeFixtures.FAKE_APP_SECRET))
        error.addSuppressed(IOException("suppressed " + ProbeFixtures.FAKE_ACCESS_TOKEN))
        error.addSuppressed(IOException("suppressed " + ProbeFixtures.FAKE_APP_KEY))
        val fields = ProbeThrowableTelemetry.fields(error, scrubber::scrub)

        val message = fields.text("error_message")!!
        assertTrue(message.length <= ProbeThrowableTelemetry.MAX_MESSAGE_LENGTH)
        assertTrue(message.startsWith("x".repeat(150) + "[REDACT"))
        assertEquals("cause [REDACTED]", fields.text("cause_1_message"))
        assertEquals("suppressed [REDACTED]", fields.text("suppressed_1_message"))
        assertEquals("suppressed [REDACTED]", fields.text("suppressed_2_message"))
        assertNoSecretsOrForbiddenKeys(fields.toString(), listOf(fields))
    }

    @Test
    fun messagesNamingCredentialFieldsAreWithheldEvenWhenTheValueIsUnknown() {
        listOf(
            "Authorization: Bearer some-unregistered-value",
            "bad appkey=unregistered",
            "approval_key rejected",
            "access-token expired",
        ).forEach { raw ->
            assertEquals(raw, ProbeRedaction.REDACTED, ProbeThrowableTelemetry.message(IOException(raw), identity))
        }
    }

    // ---------------------------------------------------------------- Stream session: response, attempt, security (§9, §12, §14)

    private class StreamHarness(root: File, val scrubber: SecretScrubber = SecretScrubber()) {
        val sessionId = ProbeSessionId.create(1_790_000_000_000L)
        val store = ProbeEvidenceStore(root, scrubber)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock()
        val recorder = ProbeRecorder(sessionId, store, clock) { fail("storage failed") }.also { it.start(scope) }
        val network = FakeNetwork()
        val session = ProbeStreamSession(
            network = network,
            allowlist = ProbeWebSocketAllowlist(ProbeEndpoints.KIS_VIRTUAL),
            approvalKey = ProbeSecret(ProbeFixtures.FAKE_APPROVAL_KEY),
            recorder = recorder,
            scrub = scrubber::scrub,
            clock = clock,
            scope = scope,
            tracker = StreamContinuityTracker(),
            onStatus = {},
            backoffSeconds = listOf(0),
        )

        suspend fun finish() {
            session.stopAndJoin()
            recorder.close()
            scope.cancel()
        }
    }

    private suspend fun awaitCallbacks(h: StreamHarness, count: Int) {
        kotlinx.coroutines.withTimeout(5_000) { while (h.network.callbacks.size < count) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun failureWithoutResponseRecordsOnlyResponsePresentFalse() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        h.clock.nanos += 168_000_000
        h.network.callbacks.single().onFailure(IOException("unexpected end of stream on http://ops.koreainvestment.com:31000/..."), null)
        h.finish()

        val failure = events(h.store, h.sessionId).ofType(ProbeEventType.WS_FAILURE).single()
        assertEquals("false", failure.text("response_present"))
        assertFalse(failure.containsKey("response_code"))
        assertEquals("1", failure.text("attempt"))
        assertEquals("168000000", failure.text("since_attempt_start_nanos"))
        assertEquals("false", failure.text("connection_opened"))
    }

    @Test
    fun failureWithResponseRecordsTheStatusCodeAndNothingElseOfIt() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        h.network.callbacks.single().onFailure(ProtocolException("Expected HTTP 101 response but was '403 Forbidden'"), 403)
        h.finish()

        val failure = events(h.store, h.sessionId).ofType(ProbeEventType.WS_FAILURE).single()
        assertEquals("true", failure.text("response_present"))
        assertEquals("403", failure.text("response_code"))
        assertEquals(
            setOf(
                "session_id", "event_type", "wall_time_epoch_ms", "elapsed_realtime_nanos",
                "response_present", "response_code",
                "error_class", "error_message", "suppressed_count",
                "attempt", "since_attempt_start_nanos", "connection_opened", "pingpong_count_this_connection",
            ),
            failure.keys,
        )
    }

    @Test
    fun stageEventsCarryTheAttemptOfTheCallbacksThatReportedThem() = runBlocking {
        val h = StreamHarness(temp.root)
        h.session.start()
        val first = h.network.callbacks.single()
        first.onConnectStage(ProbeConnectStage.DNS_START, buildJsonObject { put("host_is_provider_endpoint", true) })
        first.onFailure(IOException("first"), null)
        awaitCallbacks(h, 2)
        val second = h.network.callbacks[1]
        h.clock.nanos += 5_000
        second.onConnectStage(ProbeConnectStage.UPGRADE_RESPONSE, buildJsonObject { put("response_code", 101) })
        first.onConnectStage(ProbeConnectStage.DNS_END, buildJsonObject { put("result", "SUCCESS") })
        h.finish()

        val all = events(h.store, h.sessionId)
        assertEquals(listOf("1", "2"), all.ofType(ProbeEventType.WS_CONNECTING).map { it.text("attempt") })
        assertEquals("1", all.ofType(ProbeEventType.WS_DNS_START).single().text("attempt"))
        val upgrade = all.ofType(ProbeEventType.WS_UPGRADE_RESPONSE).single()
        assertEquals("2", upgrade.text("attempt"))
        assertEquals("5000", upgrade.text("since_attempt_start_nanos"))
        assertTrue("an inactive attempt reports nothing", all.ofType(ProbeEventType.WS_DNS_END).isEmpty())
    }

    @Test
    fun recordedFailureAndStageEvidenceLeaksNoSecretOrForbiddenKey() = runBlocking {
        val h = StreamHarness(temp.root, registeredScrubber())
        h.session.start()
        val callbacks = h.network.callbacks.single()
        callbacks.onConnectStage(ProbeConnectStage.STAGE_START, buildJsonObject { put("tcp_connection_observable", false) })
        callbacks.onConnectStage(ProbeConnectStage.DNS_END, buildJsonObject { put("result", "SUCCESS"); put("address_count", 1) })
        val error = IOException(
            "y".repeat(155) + ProbeFixtures.FAKE_APPROVAL_KEY,
            IOException("nested " + ProbeFixtures.FAKE_APP_SECRET, IOException("Authorization: Bearer " + ProbeFixtures.FAKE_ACCESS_TOKEN)),
        )
        error.addSuppressed(IOException("suppressed " + ProbeFixtures.FAKE_APP_KEY))
        callbacks.onFailure(error, 400)
        h.finish()

        val all = events(h.store, h.sessionId)
        val failure = all.ofType(ProbeEventType.WS_FAILURE).single()
        assertEquals("400", failure.text("response_code"))
        assertEquals(ProbeRedaction.REDACTED, failure.text("cause_2_message"))
        all.flatMap { it.values }.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.forEach {
            assertTrue("unbounded value", it.length <= 200)
        }
        assertNoSecretsOrForbiddenKeys(ProbeFixtures.readAll(temp.root), all)
    }

    // ---------------------------------------------------------------- DNS wrapper (§15)

    private class StageSink(private val failOnReport: Boolean = false) : ProbeSocketCallbacks {
        val stages = CopyOnWriteArrayList<Pair<ProbeConnectStage, JsonObject>>()
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val failureCode = AtomicReference<Int?>()
        override fun onOpen() = opened.countDown()
        override fun onText(text: String) = Unit
        override fun onClosed(code: Int) = closed.countDown()
        override fun onFailure(error: Throwable) = onFailure(error, null)
        override fun onFailure(error: Throwable, responseCode: Int?) {
            failure.set(error)
            failureCode.set(responseCode)
            failed.countDown()
        }
        override fun onConnectStage(stage: ProbeConnectStage, fields: JsonObject) {
            if (failOnReport) throw IllegalStateException("telemetry sink failure")
            stages += stage to fields
        }
        fun stageNames() = stages.map { it.first }
    }

    private class FakeDns(private val result: () -> List<InetAddress>) : Dns {
        val calls = AtomicInteger()
        val hosts = CopyOnWriteArrayList<String>()
        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            hosts += hostname
            return result()
        }
    }

    private fun ticks(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun dnsWrapperDelegatesOnceAndReturnsTheExactAddresses() {
        val addresses = listOf(
            InetAddress.getByAddress("ops.koreainvestment.com", byteArrayOf(10, 0, 0, 1)),
            InetAddress.getByAddress("ops.koreainvestment.com", byteArrayOf(10, 0, 0, 2)),
            InetAddress.getByAddress("ops.koreainvestment.com", ByteArray(16).also { it[15] = 1 }),
        )
        val delegate = FakeDns { addresses }
        val sink = StageSink()
        val result = ProbeTelemetryDns(delegate, sink, ticks(1_000, 51_000)).lookup("ops.koreainvestment.com")

        assertSame(addresses, result)
        assertEquals(1, delegate.calls.get())
        assertEquals(listOf("ops.koreainvestment.com"), delegate.hosts.toList())
        assertEquals(listOf(ProbeConnectStage.DNS_START, ProbeConnectStage.DNS_END), sink.stageNames())
        val start = sink.stages[0].second
        assertEquals("true", start.text("host_is_provider_endpoint"))
        assertEquals("ops.koreainvestment.com", start.text("host"))
        val end = sink.stages[1].second
        assertEquals(
            mapOf("result" to "SUCCESS", "elapsed_nanos" to "50000", "address_count" to "3", "ipv4_count" to "2", "ipv6_count" to "1"),
            end.mapValues { it.value.jsonPrimitive.content },
        )
        assertFalse("no literal address is recorded", sink.stages.toString().contains("10.0.0."))
    }

    @Test
    fun dnsWrapperRethrowsTheOriginalFailureWithoutRetrying() {
        val original = UnknownHostException("ops.koreainvestment.com")
        val delegate = FakeDns { throw original }
        val sink = StageSink()
        try {
            ProbeTelemetryDns(delegate, sink, ticks(10, 30)).lookup("ops.koreainvestment.com")
            fail("expected the delegate failure")
        } catch (thrown: UnknownHostException) {
            assertSame(original, thrown)
        }
        assertEquals(1, delegate.calls.get())
        val end = sink.stages.single { it.first == ProbeConnectStage.DNS_END }.second
        assertEquals("FAILURE", end.text("result"))
        assertEquals("20", end.text("elapsed_nanos"))
        assertEquals("UnknownHostException", end.text("error_class"))
    }

    @Test
    fun dnsWrapperRecordsNoHostNameForAnyOtherHost() {
        val sink = StageSink()
        ProbeTelemetryDns(FakeDns { emptyList() }, sink, ticks(0, 0)).lookup("example.com")

        val start = sink.stages.first().second
        assertEquals("false", start.text("host_is_provider_endpoint"))
        assertFalse(start.containsKey("host"))
        assertFalse(sink.stages.toString().contains("example.com"))
    }

    @Test
    fun dnsWrapperResultIsUnaffectedByAFailingTelemetrySink() {
        val addresses = listOf(InetAddress.getByAddress("ops.koreainvestment.com", byteArrayOf(10, 0, 0, 1)))
        val delegate = FakeDns { addresses }
        assertSame(addresses, ProbeTelemetryDns(delegate, StageSink(failOnReport = true)).lookup("ops.koreainvestment.com"))
        assertEquals(1, delegate.calls.get())
    }

    // ---------------------------------------------------------------- Stage interceptor (§16)

    private class FakeChain(private val request: Request, private val outcome: (Request) -> Response) : Interceptor.Chain {
        val proceeded = CopyOnWriteArrayList<Request>()
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceeded += request
            return outcome(request)
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private val upgradeRequest = Request.Builder()
        .url("http://ops.koreainvestment.com:31000/tryitout")
        .header("Upgrade", "websocket")
        .header("authorization", "Bearer " + ProbeFixtures.FAKE_ACCESS_TOKEN)
        .build()

    @Test
    fun interceptorProceedsOnceWithTheSameRequestAndReturnsTheSameResponse() {
        val response = Response.Builder()
            .request(upgradeRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .header("Set-Cookie", "session=" + ProbeFixtures.FAKE_APP_KEY)
            .build()
        val chain = FakeChain(upgradeRequest) { response }
        val sink = StageSink()
        val returned = ProbeConnectStageInterceptor(sink, ticks(100, 400)).intercept(chain)

        assertSame(response, returned)
        assertEquals(1, chain.proceeded.size)
        assertSame(upgradeRequest, chain.proceeded.single())
        assertEquals(listOf(ProbeConnectStage.STAGE_START, ProbeConnectStage.UPGRADE_RESPONSE), sink.stageNames())
        assertEquals(
            mapOf("elapsed_nanos" to "300", "response_code" to "101"),
            sink.stages[1].second.mapValues { it.value.jsonPrimitive.content },
        )
        assertNoSecretsOrForbiddenKeys(sink.stages.toString(), sink.stages.map { it.second })
    }

    @Test
    fun interceptorRethrowsTheOriginalExceptionAndNeverRetries() {
        val original = IOException("unexpected end of stream on http://ops.koreainvestment.com:31000/...")
        val chain = FakeChain(upgradeRequest) { throw original }
        val sink = StageSink()
        try {
            ProbeConnectStageInterceptor(sink, ticks(0, 7)).intercept(chain)
            fail("expected the original exception")
        } catch (thrown: IOException) {
            assertSame(original, thrown)
        }
        assertEquals(1, chain.proceeded.size)
        assertEquals(listOf(ProbeConnectStage.STAGE_START, ProbeConnectStage.STAGE_FAILURE), sink.stageNames())
        assertEquals(
            mapOf("elapsed_nanos" to "7", "error_class" to "IOException"),
            sink.stages[1].second.mapValues { it.value.jsonPrimitive.content },
        )
        assertFalse(sink.stages.toString().contains("unexpected end of stream"))
    }

    @Test
    fun interceptorResultIsUnaffectedByAFailingTelemetrySink() {
        val response = Response.Builder().request(upgradeRequest).protocol(Protocol.HTTP_1_1).code(101).message("ok").build()
        val chain = FakeChain(upgradeRequest) { response }
        assertSame(response, ProbeConnectStageInterceptor(StageSink(failOnReport = true)).intercept(chain))
        assertEquals(1, chain.proceeded.size)
    }

    // ---------------------------------------------------------------- Real OkHttp transport against a local server only

    private fun loopbackServer(): MockWebServer = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    private fun loopbackEndpoints(server: MockWebServer) =
        ProbeEndpoints.KIS_VIRTUAL.copy(wsHost = "127.0.0.1", wsPort = server.port)

    /** Completes the close handshake so no half-closed socket outlives the test. */
    private class ClosingServerSide : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Test
    fun realUpgradeReportsStagesAndSendsTheSameRequestAsPlainOkHttp() {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(ClosingServerSide()))
            server.enqueue(MockResponse().withWebSocketUpgrade(ClosingServerSide()))
            val sink = StageSink()
            val socket = KisProbeTransport.forEndpoints(loopbackEndpoints(server)).connect(sink)
            assertTrue("probe upgrade failed: ${sink.failure.get()}", sink.opened.await(5, TimeUnit.SECONDS))
            val probeRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

            val plainOpened = CountDownLatch(1)
            val plainClosed = CountDownLatch(1)
            val plainClient = OkHttpClient()
            val plain = plainClient.newWebSocket(
                Request.Builder().url(loopbackEndpoints(server).wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = plainOpened.countDown()
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = plainClosed.countDown()
                },
            )
            assertTrue(plainOpened.await(5, TimeUnit.SECONDS))
            val plainRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

            assertEquals(plainRequest.requestLine, probeRequest.requestLine)
            assertEquals(plainRequest.headers.names(), probeRequest.headers.names())
            plainRequest.headers.names().filterNot { it.equals("Sec-WebSocket-Key", ignoreCase = true) }.forEach {
                assertEquals(it, plainRequest.headers[it], probeRequest.headers[it])
            }
            assertEquals(listOf(ProbeConnectStage.STAGE_START, ProbeConnectStage.UPGRADE_RESPONSE), sink.stageNames())
            assertEquals("101", sink.stages[1].second.text("response_code"))
            socket.close(1000, "test done")
            plain.close(1000, "test done")
            assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
            assertTrue(plainClosed.await(5, TimeUnit.SECONDS))
            plainClient.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun peerCloseBeforeAnyResponseSurfacesAsPlainIOExceptionWithNoResponse() {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val sink = StageSink()
            KisProbeTransport.forEndpoints(loopbackEndpoints(server)).connect(sink)
            assertTrue(sink.failed.await(5, TimeUnit.SECONDS))

            val failure = sink.failure.get()!!
            assertEquals(IOException::class.java, failure.javaClass)
            assertTrue(failure.message!!.startsWith("unexpected end of stream"))
            assertNull(sink.failureCode.get())
            assertEquals(1, server.requestCount)
            assertEquals(listOf(ProbeConnectStage.STAGE_START, ProbeConnectStage.STAGE_FAILURE), sink.stageNames())
            assertEquals("IOException", sink.stages[1].second.text("error_class"))
        }
    }

    @Test
    fun nonUpgradeResponseIsRecordedByStatusCodeAndReachesOnFailureWithItsCode() {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden " + ProbeFixtures.FAKE_APP_KEY))
            val sink = StageSink()
            KisProbeTransport.forEndpoints(loopbackEndpoints(server)).connect(sink)
            assertTrue(sink.failed.await(5, TimeUnit.SECONDS))

            assertEquals(ProtocolException::class.java, sink.failure.get()!!.javaClass)
            assertEquals(403, sink.failureCode.get())
            assertEquals(listOf(ProbeConnectStage.STAGE_START, ProbeConnectStage.UPGRADE_RESPONSE), sink.stageNames())
            assertEquals(setOf("elapsed_nanos", "response_code"), sink.stages[1].second.keys)
            assertEquals("403", sink.stages[1].second.text("response_code"))
            assertFalse(sink.stages.toString().contains(ProbeFixtures.FAKE_APP_KEY))
        }
    }

    // ---------------------------------------------------------------- Retry invariants (§17) and unchanged configuration (§11)

    @Test
    fun backoffIsOneTwoFiveTenThenThirtyForeverWithoutJitterAndResetsOnOpen() = runTest {
        val sessionId = ProbeSessionId.create(1_790_000_000_000L)
        val store = ProbeEvidenceStore(temp.root, SecretScrubber())
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = ProbeRecorder(sessionId, store, FakeClock()) { fail("storage failed") }.also { it.start(ioScope) }
        val network = FakeNetwork()
        val session = ProbeStreamSession(
            network = network,
            allowlist = ProbeWebSocketAllowlist(ProbeEndpoints.KIS_VIRTUAL),
            approvalKey = ProbeSecret(ProbeFixtures.FAKE_APPROVAL_KEY),
            recorder = recorder,
            scrub = { it },
            clock = FakeClock(),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            tracker = StreamContinuityTracker(),
            onStatus = {},
        )
        session.start()

        fun failAndExpectReconnectAfter(seconds: Long) {
            val before = network.callbacks.size
            network.callbacks.last().onFailure(IOException("x"), null)
            advanceTimeBy(seconds * 1000 - 1)
            runCurrent()
            assertEquals("reconnected before ${seconds}s", before, network.callbacks.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("no reconnect at exactly ${seconds}s", before + 1, network.callbacks.size)
        }

        val expected = listOf<Long>(1, 2, 5, 10, 30, 30, 30, 30, 30, 30, 30, 30)
        expected.forEach(::failAndExpectReconnectAfter)
        network.callbacks.last().onOpen()
        failAndExpectReconnectAfter(1)

        session.stopAndJoin()
        recorder.close()
        ioScope.cancel()
        val scheduled = events(store, sessionId).ofType(ProbeEventType.WS_RECONNECT_SCHEDULED).map { it.text("delay_seconds")!!.toLong() }
        assertEquals(expected + 1L, scheduled)
        assertEquals((expected.size + 1).toLong(), session.reconnectCount.get())
    }

    @Test
    fun connectionConfigurationAndRetrySourceAreUnchanged() {
        val moduleDir = listOf(File("."), File("app")).first { File(it, "src/debug").isDirectory }
        val probeDir = File(moduleDir, "src/debug/java/com/mirunubi/bjstock/probe/intraday")
        val transport = File(probeDir, "KisProbeTransport.kt").readText()
        listOf(
            ".followRedirects(false)",
            ".followSslRedirects(false)",
            ".connectTimeout(15, TimeUnit.SECONDS)",
            ".readTimeout(20, TimeUnit.SECONDS)",
            ".callTimeout(20, TimeUnit.SECONDS)",
            ".readTimeout(0, TimeUnit.SECONDS)",
            ".addInterceptor(ProbeWebSocketEndpointInterceptor(ProbeWebSocketAllowlist(endpoints)))",
            "Request.Builder().url(endpoints.wsUrl).build()",
        ).forEach { assertTrue("missing $it", transport.contains(it)) }
        listOf("retryOnConnectionFailure", "pingInterval", "addNetworkInterceptor", "eventListener", "HttpLoggingInterceptor", ".header(")
            .forEach { token ->
                val wsSection = transport.substringAfter("override fun connect(").substringBefore("private suspend fun postJson")
                assertFalse("connect path contains $token", wsSection.contains(token))
            }
        assertFalse(transport.contains("retryOnConnectionFailure") || transport.contains("pingInterval"))

        val stream = File(probeDir, "ProbeStreamSession.kt").readText()
        assertTrue(stream.contains("private val backoffSeconds: List<Long> = listOf(1, 2, 5, 10, 30),"))
        assertTrue(stream.contains("backoffSeconds[minOf(consecutiveFailures, backoffSeconds.lastIndex)]"))
        assertTrue(stream.contains("consecutiveFailures = 0"))
        assertFalse(stream.contains("Random") || stream.contains("jitter", ignoreCase = true))
        assertEquals("ws://ops.koreainvestment.com:31000/tryitout", ProbeEndpoints.KIS_VIRTUAL.wsUrl)
    }
}
