package com.mirunubi.bjstock.probe.intraday

import com.mirunubi.bjstock.core.kis.KisEnvironment
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProbeGateAndAllowlistTest {
    private val rest = ProbeRestAllowlist(ProbeEndpoints.KIS_VIRTUAL)
    private val ws = ProbeWebSocketAllowlist(ProbeEndpoints.KIS_VIRTUAL)

    private fun minuteBarUrl(
        host: String = "openapivts.koreainvestment.com",
        port: Int = 29443,
        scheme: String = "https",
        venue: String = "J",
        symbol: String = "005930",
        extra: Pair<String, String>? = null,
    ): HttpUrl = HttpUrl.Builder()
        .scheme(scheme).host(host).port(port)
        .encodedPath(ProbePaths.MINUTE_BARS)
        .addQueryParameter("FID_COND_MRKT_DIV_CODE", venue)
        .addQueryParameter("FID_INPUT_ISCD", symbol)
        .addQueryParameter("FID_INPUT_HOUR_1", "093000")
        .addQueryParameter("FID_PW_DATA_INCU_YN", "N")
        .addQueryParameter("FID_ETC_CLS_CODE", "")
        .apply { extra?.let { addQueryParameter(it.first, it.second) } }
        .build()

    private fun denied(decision: AllowlistDecision): String = (decision as? AllowlistDecision.Denied)?.reason
        ?: error("expected Denied but was $decision")

    // 1. VIRTUAL accepted
    @Test
    fun virtualEnvironmentWithCredentialsPasses() = runBlocking {
        val result = VirtualGatePass.evaluate(KisEnvironment.VIRTUAL, ProbeEndpoints.KIS_VIRTUAL) { true }
        assertTrue(result is VirtualGatePass.Result.Passed)
        assertEquals(ProbeEndpoints.KIS_VIRTUAL, (result as VirtualGatePass.Result.Passed).pass.endpoints)
    }

    // 2. PRODUCTION rejected, with no fallback even when credentials exist
    @Test
    fun productionEnvironmentIsRefusedWithoutFallback() = runBlocking {
        var credentialLookups = 0
        val result = VirtualGatePass.evaluate(KisEnvironment.PRODUCTION, ProbeEndpoints.KIS_VIRTUAL) { credentialLookups++; true }
        assertEquals(VirtualGatePass.Result.Refused(ProbeErrorCode.NON_VIRTUAL_ENVIRONMENT), result)
        assertEquals(0, credentialLookups)
    }

    @Test
    fun nonOfficialVirtualEndpointsAreRefused() = runBlocking {
        val productionLike = ProbeEndpoints.KIS_VIRTUAL.copy(restHost = "openapi.koreainvestment.com", restPort = 9443, wsPort = 21000)
        val result = VirtualGatePass.evaluate(KisEnvironment.VIRTUAL, productionLike) { true }
        assertEquals(VirtualGatePass.Result.Refused(ProbeErrorCode.NON_VIRTUAL_ENVIRONMENT), result)
    }

    @Test
    fun missingVirtualCredentialsRefusesWithSafeMessage() = runBlocking {
        val result = VirtualGatePass.evaluate(KisEnvironment.VIRTUAL, ProbeEndpoints.KIS_VIRTUAL) { false }
        assertEquals(VirtualGatePass.Result.Refused(ProbeErrorCode.VIRTUAL_CREDENTIAL_MISSING), result)
        assertEquals("VIRTUAL quotation credentials are not configured.", ProbeErrorCode.VIRTUAL_CREDENTIAL_MISSING.safeMessage)
    }

    // 3. allowed REST endpoints
    @Test
    fun allowlistedRestEndpointsAreAllowed() {
        val base = "https://openapivts.koreainvestment.com:29443"
        assertEquals(AllowlistDecision.Allowed, rest.check("POST", "$base${ProbePaths.TOKEN}".toHttpUrl(), null))
        assertEquals(AllowlistDecision.Allowed, rest.check("POST", "$base${ProbePaths.APPROVAL}".toHttpUrl(), null))
        assertEquals(AllowlistDecision.Allowed, rest.check("GET", minuteBarUrl(), "FHKST03010200"))
        assertEquals(AllowlistDecision.Allowed, rest.check("GET", minuteBarUrl(symbol = "000660"), "FHKST03010200"))
    }

    // 4. unknown REST endpoint rejected
    @Test
    fun unknownRestPathsAreDenied() {
        val base = "https://openapivts.koreainvestment.com:29443"
        assertEquals("PATH_NOT_ALLOWED", denied(rest.check("GET", "$base/uapi/domestic-stock/v1/quotations/inquire-price".toHttpUrl(), "FHKST01010100")))
        assertEquals("PATH_NOT_ALLOWED", denied(rest.check("POST", "$base/oauth2/revokeP".toHttpUrl(), null)))
        assertEquals("PATH_NOT_ALLOWED", denied(rest.check("GET", "$base/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice/".toHttpUrl(), "FHKST03010200")))
    }

    // 5. /trading/ rejected, regardless of case or position
    @Test
    fun tradingPathsAreAlwaysDenied() {
        val base = "https://openapivts.koreainvestment.com:29443"
        listOf(
            "/uapi/domestic-stock/v1/trading/order-cash",
            "/uapi/domestic-stock/v1/trading/inquire-balance",
            "/uapi/domestic-stock/v1/TRADING/order-cash",
            "/uapi/domestic-stock/v1/quotations/trading/x",
        ).forEach { path ->
            assertEquals(path, "TRADING_PATH_FORBIDDEN", denied(rest.check("POST", "$base$path".toHttpUrl(), "VTTC0802U")))
            assertEquals(path, "TRADING_PATH_FORBIDDEN", denied(rest.check("GET", "$base$path".toHttpUrl(), "FHKST03010200")))
        }
    }

    // 6. wrong host / port / scheme rejected
    @Test
    fun wrongHostPortOrSchemeIsDenied() {
        assertEquals("HOST_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(host = "openapi.koreainvestment.com", port = 29443), "FHKST03010200")))
        assertEquals("HOST_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(host = "openapivts.koreainvestment.com.evil.example"), "FHKST03010200")))
        assertEquals("PORT_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(port = 9443), "FHKST03010200")))
        assertEquals("SCHEME_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(scheme = "http"), "FHKST03010200")))
    }

    @Test
    fun minuteBarRequestShapeIsExact() {
        assertEquals("TR_ID_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(), "FHKST01010100")))
        assertEquals("TR_ID_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(), null)))
        assertEquals("METHOD_NOT_ALLOWED", denied(rest.check("POST", minuteBarUrl(), "FHKST03010200")))
        assertEquals("VENUE_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(venue = "NX"), "FHKST03010200")))
        assertEquals("VENUE_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(venue = "UN"), "FHKST03010200")))
        assertEquals("SYMBOL_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(symbol = "035420"), "FHKST03010200")))
        assertEquals("QUERY_NOT_ALLOWED", denied(rest.check("GET", minuteBarUrl(extra = "CANO" to "1"), "FHKST03010200")))
        val base = "https://openapivts.koreainvestment.com:29443"
        assertEquals("QUERY_NOT_ALLOWED", denied(rest.check("POST", "$base${ProbePaths.TOKEN}?x=1".toHttpUrl(), null)))
        assertEquals("TR_ID_NOT_ALLOWED", denied(rest.check("POST", "$base${ProbePaths.TOKEN}".toHttpUrl(), "VTTC0802U")))
        assertEquals("METHOD_NOT_ALLOWED", denied(rest.check("GET", "$base${ProbePaths.APPROVAL}".toHttpUrl(), null)))
    }

    @Test
    fun restInterceptorBlocksDeniedRequestBeforeAnyNetworkIo() {
        MockWebServer().use { server ->
            server.start()
            val local = ProbeEndpoints.KIS_VIRTUAL.copy(restScheme = "http", restHost = server.hostName, restPort = server.port)
            val client = OkHttpClient.Builder()
                .addInterceptor(ProbeRestAllowlistInterceptor(ProbeRestAllowlist(local)))
                .build()
            val request = Request.Builder()
                .url(server.url("/uapi/domestic-stock/v1/trading/order-cash"))
                .header("tr_id", "VTTC0802U")
                .get()
                .build()
            try {
                client.newCall(request).execute().close()
                fail("expected allowlist denial")
            } catch (expected: ProbeAllowlistDeniedException) {
                assertEquals("TRADING_PATH_FORBIDDEN", expected.reason)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun transportMinuteBarRequestIsExactAndPassesTheAllowlistAgainstLocalServerOnly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"ok","output2":[{"stck_bsop_date":"20261002","stck_cntg_hour":"093000"}]}""",
                ),
            )
            server.start()
            val local = ProbeEndpoints.KIS_VIRTUAL.copy(restScheme = "http", restHost = server.hostName, restPort = server.port)
            val transport = KisProbeTransport.forEndpoints(local)
            val result = transport.fetchTodayMinuteBars(ProbeSecret(ProbeFixtures.FAKE_ACCESS_TOKEN), ProbeFixtures.CREDENTIALS, "005930", "093000")
            assertEquals(1, result.rowCount)
            assertEquals("093000", result.firstRowTradeHour)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals(ProbePaths.MINUTE_BARS, recorded.requestUrl!!.encodedPath)
            assertEquals("FHKST03010200", recorded.getHeader("tr_id"))
            assertEquals("P", recorded.getHeader("custtype"))
            assertEquals("J", recorded.requestUrl!!.queryParameter("FID_COND_MRKT_DIV_CODE"))
        }
    }

    // 7. H0STCNT0 accepted
    @Test
    fun h0stcnt0SubscriptionIsAccepted() {
        assertEquals(AllowlistDecision.Allowed, ws.checkSubscription("H0STCNT0", "005930", "1"))
    }

    // 8. other TRs rejected (including NXT / integrated variants)
    @Test
    fun otherTrIdsAreRejected() {
        listOf("H0STASP0", "H0NXCNT0", "H0UNCNT0", "H0STCNI0", "H0STCNT1", "h0stcnt0", "").forEach { tr ->
            assertEquals(tr, "TR_NOT_ALLOWED", denied(ws.checkSubscription(tr, "005930", "1")))
        }
        assertEquals("TR_TYPE_NOT_ALLOWED", denied(ws.checkSubscription("H0STCNT0", "005930", "2")))
    }

    // 9. both approved symbols accepted
    @Test
    fun approvedSymbolsAreAccepted() {
        assertEquals(AllowlistDecision.Allowed, ws.checkSubscription("H0STCNT0", "005930", "1"))
        assertEquals(AllowlistDecision.Allowed, ws.checkSubscription("H0STCNT0", "000660", "1"))
    }

    // 10. unapproved symbol rejected
    @Test
    fun unapprovedSymbolsAreRejected() {
        listOf("035420", "005935", "A005930", "005930 ", "").forEach { symbol ->
            assertEquals(symbol, "SYMBOL_NOT_ALLOWED", denied(ws.checkSubscription("H0STCNT0", symbol, "1")))
        }
    }

    @Test
    fun webSocketEndpointIsExactVirtualOnly() {
        // OkHttp presents ws:// upgrade requests to interceptors as http://.
        assertEquals(AllowlistDecision.Allowed, ws.checkEndpoint("http://ops.koreainvestment.com:31000/tryitout".toHttpUrl()))
        assertEquals("PORT_NOT_ALLOWED", denied(ws.checkEndpoint("http://ops.koreainvestment.com:21000/tryitout".toHttpUrl())))
        assertEquals("SCHEME_NOT_ALLOWED", denied(ws.checkEndpoint("https://ops.koreainvestment.com:31000/tryitout".toHttpUrl())))
        assertEquals("HOST_NOT_ALLOWED", denied(ws.checkEndpoint("http://ops.example.com:31000/tryitout".toHttpUrl())))
        assertEquals("PATH_NOT_ALLOWED", denied(ws.checkEndpoint("http://ops.koreainvestment.com:31000/".toHttpUrl())))
        assertEquals("QUERY_NOT_ALLOWED", denied(ws.checkEndpoint("http://ops.koreainvestment.com:31000/tryitout?x=1".toHttpUrl())))
        assertEquals("TRADING_PATH_FORBIDDEN", denied(ws.checkEndpoint("http://ops.koreainvestment.com:31000/trading/".toHttpUrl())))
        assertEquals("ws://ops.koreainvestment.com:31000/tryitout", ProbeEndpoints.KIS_VIRTUAL.wsUrl)
    }

    private class SocketProbe : ProbeSocketCallbacks {
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        override fun onOpen() = opened.countDown()
        override fun onText(text: String) = Unit
        override fun onClosed(code: Int) = Unit
        override fun onFailure(error: Throwable) {
            failure.set(error)
            failed.countDown()
        }
    }

    private class FailingListener : WebSocketListener() {
        val failed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure.set(t)
            failed.countDown()
        }
    }

    /** Terminal application interceptor: proves the probe interceptor let the request through, then stops before DNS/connect. */
    private class StopBeforeNetwork : Interceptor {
        val reached = AtomicReference<Request?>()
        override fun intercept(chain: Interceptor.Chain): Response {
            reached.set(chain.request())
            throw IOException("test stops before any network I/O")
        }
    }

    // F-9: actual OkHttp WebSocket upgrade through the probe transport and interceptor, against a local server only.
    @Test
    fun realOkHttpWebSocketUpgradePassesTheProbeInterceptor() {
        MockWebServer().use { server ->
            val serverMessages = LinkedBlockingQueue<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverMessages.add(text)
                    }
                }),
            )
            server.start()
            val local = ProbeEndpoints.KIS_VIRTUAL.copy(wsHost = server.hostName, wsPort = server.port)
            val callbacks = SocketProbe()
            val socket = KisProbeTransport.forEndpoints(local).connect(callbacks)
            assertTrue("upgrade failed: ${callbacks.failure.get()?.javaClass?.simpleName}", callbacks.opened.await(5, TimeUnit.SECONDS))
            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/tryitout", upgrade.path)
            assertEquals("websocket", upgrade.getHeader("Upgrade"))
            assertTrue(socket.send(ProbeSubscriptionMessage.build(ProbeSecret(ProbeFixtures.FAKE_APPROVAL_KEY), "H0STCNT0", "005930", "1")))
            val received = serverMessages.poll(5, TimeUnit.SECONDS)!!
            assertTrue(received.contains("\"tr_id\":\"H0STCNT0\""))
            socket.close(1000, "test done")
        }
    }

    @Test
    fun exactVirtualEndpointIsRecognizedInOkHttpWebSocketForm() {
        val stop = StopBeforeNetwork()
        val client = OkHttpClient.Builder()
            .addInterceptor(ProbeWebSocketEndpointInterceptor(ws))
            .addInterceptor(stop)
            .build()
        val listener = FailingListener()
        client.newWebSocket(Request.Builder().url(ProbeEndpoints.KIS_VIRTUAL.wsUrl).build(), listener)
        assertTrue(listener.failed.await(5, TimeUnit.SECONDS))
        val seen = stop.reached.get()!!
        assertEquals("http", seen.url.scheme)
        assertEquals("ops.koreainvestment.com", seen.url.host)
        assertEquals(31000, seen.url.port)
        assertEquals("/tryitout", seen.url.encodedPath)
        assertEquals("websocket", seen.header("Upgrade"))
        assertFalse(listener.failure.get() is ProbeAllowlistDeniedException)
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun deniedWebSocketEndpointsAreBlockedBeforeAnyConnection() {
        MockWebServer().use { server ->
            server.start()
            listOf(
                "ws://${server.hostName}:${server.port}/tryitout" to "HOST_NOT_ALLOWED",
                "ws://ops.koreainvestment.com:21000/tryitout" to "PORT_NOT_ALLOWED",
                "wss://ops.koreainvestment.com:31000/tryitout" to "SCHEME_NOT_ALLOWED",
                "ws://ops.koreainvestment.com:31000/trading/" to "TRADING_PATH_FORBIDDEN",
            ).forEach { (url, reason) ->
                val stop = StopBeforeNetwork()
                val client = OkHttpClient.Builder()
                    .addInterceptor(ProbeWebSocketEndpointInterceptor(ws))
                    .addInterceptor(stop)
                    .build()
                val listener = FailingListener()
                client.newWebSocket(Request.Builder().url(url).build(), listener)
                assertTrue(url, listener.failed.await(5, TimeUnit.SECONDS))
                assertEquals(url, reason, (listener.failure.get() as ProbeAllowlistDeniedException).reason)
                assertNull(url, stop.reached.get())
                client.dispatcher.executorService.shutdown()
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun webSocketInterceptorRejectsNonUpgradeRequests() {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder()
                .addInterceptor(ProbeWebSocketEndpointInterceptor(ws))
                .build()
            try {
                client.newCall(Request.Builder().url(server.url("/tryitout")).build()).execute().close()
                fail("expected denial")
            } catch (expected: ProbeAllowlistDeniedException) {
                assertEquals("NOT_A_WEBSOCKET_UPGRADE", expected.reason)
            }
            assertFalse(server.requestCount > 0)
        }
    }
}
