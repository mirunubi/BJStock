package com.mirunubi.bjstock.core.kis.market

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mirunubi.bjstock.core.kis.InMemoryKisSecretStore
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisToken
import com.mirunubi.bjstock.core.kis.RecordingKisAuthLogger
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisMarketApi
import com.mirunubi.bjstock.core.network.kis.KisReadOnlyInterceptor
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class KisMarketRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryKisSecretStore
    private lateinit var logger: RecordingKisAuthLogger
    private val today: LocalDate = LocalDate.of(2026, 9, 18)

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        store = InMemoryKisSecretStore()
        logger = RecordingKisAuthLogger()
        store.saveCredentials(KisEnvironment.PRODUCTION, "TEST_APP_KEY", "TEST_APP_SECRET")
        store.saveToken(
            KisEnvironment.PRODUCTION,
            KisToken(
                accessToken = "TEST_ACCESS_TOKEN",
                tokenType = "Bearer",
                expiresAtEpochMillis = NOW + TimeUnit.HOURS.toMillis(1),
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun currentPrice_success200RtCd0() = runBlocking {
        enqueueJson(currentPriceJson())
        val quote = repository().inquireCurrentPrice("005930")
        assertEquals(72_300L, quote.currentPrice)
        assertEquals(72_100L, quote.openPrice)
        assertEquals(73_000L, quote.highPrice)
        assertEquals(71_800L, quote.lowPrice)
        assertEquals(12_345_678L, quote.volume)
        assertEquals(-1_250_000L, quote.changeRate)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/uapi/domestic-stock/v1/quotations/inquire-price"))
        assertEquals("FHKST01010100", recorded.getHeader("tr_id"))
        assertEquals("J", recorded.requestUrl!!.queryParameter("FID_COND_MRKT_DIV_CODE"))
        assertEquals("005930", recorded.requestUrl!!.queryParameter("FID_INPUT_ISCD"))
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun currentPrice_businessErrorWhenRtCdNotZero() = runBlocking {
        enqueueJson("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"TEST business error"}""")
        val error = runCatching { repository().inquireCurrentPrice("005930") }.exceptionOrNull()
        assertTrue(error is KisMarketException)
        assertEquals(KisMarketErrorKind.BUSINESS, (error as KisMarketException).kind)
        assertEquals("EGW00123", error.audit?.msgCd)
        logger.assertNoSecrets(SECRET_VALUES)
        assertTrue(logger.messages.any { it.contains("EGW00123") })
    }

    @Test
    fun currentPrice_unauthorized401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"rt_cd":"1"}"""))
        val error = runCatching { repository().inquireCurrentPrice("005930") }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.AUTHENTICATION, (error as KisMarketException).kind)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun currentPrice_http500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        val error = runCatching { repository().inquireCurrentPrice("005930") }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.HTTP, (error as KisMarketException).kind)
        assertEquals(500, error.audit?.httpCode)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun currentPrice_timeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = runCatching {
            repository(callTimeoutMillis = 300).inquireCurrentPrice("005930")
        }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.NETWORK_TIMEOUT, (error as KisMarketException).kind)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun currentPrice_malformedJson() = runBlocking {
        enqueueJson("{not-json")
        val error = runCatching { repository().inquireCurrentPrice("005930") }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.MALFORMED_RESPONSE, (error as KisMarketException).kind)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun currentPrice_invalidSymbolRejectedWithoutNetwork() = runBlocking {
        val error = runCatching { repository().inquireCurrentPrice("5930") }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.INVALID_SYMBOL, (error as KisMarketException).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun dailyBars_reverseFixtureIsSortedAscendingAndDeduped() = runBlocking {
        enqueueJson(dailyJsonNewestFirstWithDuplicate())
        val bars = repository().inquireDailyBars(
            symbol = "005930",
            startDate = LocalDate.of(2026, 9, 16),
            endDate = LocalDate.of(2026, 9, 18),
        )
        assertEquals(3, bars.size)
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 18),
            ),
            bars.map { it.tradeDate },
        )
        assertEquals(72_700L, bars.last().closePrice)
        val recorded = server.takeRequest()
        assertEquals("D", recorded.requestUrl!!.queryParameter("FID_PERIOD_DIV_CODE"))
        assertEquals("0", recorded.requestUrl!!.queryParameter("FID_ORG_ADJ_PRC"))
        assertEquals("FHKST03010100", recorded.getHeader("tr_id"))
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun dailyBars_futureOnlyRangeRejected() = runBlocking {
        val error = runCatching {
            repository().inquireDailyBars(
                symbol = "005930",
                startDate = LocalDate.of(2026, 9, 19),
                endDate = LocalDate.of(2026, 9, 20),
            )
        }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.INVALID_DATE_RANGE, (error as KisMarketException).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun dailyBars_missingTradingValueIsNull() = runBlocking {
        enqueueJson(
            """
            {
              "rt_cd":"0",
              "msg_cd":"MCA00000",
              "msg1":"TEST success",
              "output2":[
                {
                  "stck_bsop_date":"20260916",
                  "stck_oprc":"100",
                  "stck_hgpr":"110",
                  "stck_lwpr":"90",
                  "stck_clpr":"105",
                  "acml_vol":"1"
                }
              ]
            }
            """.trimIndent(),
        )
        val bars = repository().inquireDailyBars(
            "005930",
            LocalDate.of(2026, 9, 16),
            LocalDate.of(2026, 9, 16),
        )
        assertNull(bars.single().tradingValue)
    }

    private fun repository(callTimeoutMillis: Long = 5_000): KisMarketRepositoryImpl {
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(KisReadOnlyInterceptor())
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val auth = KisAuthRepository(
            api = retrofit.create(KisAuthApi::class.java),
            credentialStore = store,
            tokenStore = store,
            settingsStore = store,
            logger = logger,
            currentTimeMillis = { NOW },
            tokenUrl = { server.url("/oauth2/tokenP").toString() },
        )
        return KisMarketRepositoryImpl(
            api = retrofit.create(KisMarketApi::class.java),
            authRepository = auth,
            credentialStore = store,
            logger = logger,
            today = { today },
            baseUrl = { server.url("/").toString().trimEnd('/') },
        )
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun currentPriceJson(): String = """
        {
          "rt_cd":"0",
          "msg_cd":"MCA00000",
          "msg1":"TEST success",
          "output":{
            "stck_prpr":"72300",
            "prdy_vrss":"-900",
            "prdy_ctrt":"-1.25",
            "stck_oprc":"72100",
            "stck_hgpr":"73000",
            "stck_lwpr":"71800",
            "acml_vol":"12345678",
            "acml_tr_pbmn":"890000000000",
            "stck_bsop_date":"20260918"
          }
        }
    """.trimIndent()

    private fun dailyJsonNewestFirstWithDuplicate(): String = """
        {
          "rt_cd":"0",
          "msg_cd":"MCA00000",
          "msg1":"TEST success",
          "output2":[
            {
              "stck_bsop_date":"20260918",
              "stck_oprc":"72100",
              "stck_hgpr":"73000",
              "stck_lwpr":"71800",
              "stck_clpr":"72000",
              "acml_vol":"100",
              "acml_tr_pbmn":"1"
            },
            {
              "stck_bsop_date":"20260917",
              "stck_oprc":"71000",
              "stck_hgpr":"72000",
              "stck_lwpr":"70500",
              "stck_clpr":"71800",
              "acml_vol":"200",
              "acml_tr_pbmn":"2"
            },
            {
              "stck_bsop_date":"20260916",
              "stck_oprc":"70000",
              "stck_hgpr":"71500",
              "stck_lwpr":"69800",
              "stck_clpr":"71000",
              "acml_vol":"300",
              "acml_tr_pbmn":"3"
            },
            {
              "stck_bsop_date":"20260918",
              "stck_oprc":"72100",
              "stck_hgpr":"73000",
              "stck_lwpr":"71800",
              "stck_clpr":"72700",
              "acml_vol":"12345678",
              "acml_tr_pbmn":"4"
            }
          ]
        }
    """.trimIndent()

    companion object {
        private const val NOW = 1_700_000_000_000L
        private val SECRET_VALUES = listOf(
            "TEST_APP_KEY",
            "TEST_APP_SECRET",
            "TEST_ACCESS_TOKEN",
        )
    }
}
