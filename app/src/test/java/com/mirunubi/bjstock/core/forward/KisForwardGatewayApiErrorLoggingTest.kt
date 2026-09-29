package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.kis.InMemoryKisSecretStore
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisToken
import com.mirunubi.bjstock.core.kis.RecordingKisAuthLogger
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketRepositoryImpl
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import com.mirunubi.bjstock.core.kis.market.KisRequestPolicy
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import com.mirunubi.bjstock.core.marketdata.SyncDailyBarsFromLatestUseCase
import com.mirunubi.bjstock.core.marketdata.SyncHistoricalDailyBarsUseCase
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisMarketApi
import com.mirunubi.bjstock.core.network.kis.KisReadOnlyInterceptor
import java.time.Instant
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

/**
 * Repository and gateway share one api_error_logs table, as in production DI.
 * Verifies error_type taxonomy and one row per actual failure.
 */
@RunWith(RobolectricTestRunner::class)
class KisForwardGatewayApiErrorLoggingTest {
    private lateinit var server: MockWebServer
    private lateinit var database: BJStockDatabase
    private lateinit var localRepository: MarketDataLocalRepository
    private lateinit var gateway: KisForwardMarketDataGateway
    private val sleeps = mutableListOf<Long>()
    private var instrumentId: Long = 0

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
        gateway = gateway()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun providerBusinessRejection_isKisBusinessError_nonRetryable_singleRow() = runBlocking {
        seedLatestBar()
        enqueue(200, """{"rt_cd":"1","msg_cd":"TEST0001","msg1":"TEST rejected"}""")
        val outcome = sync()
        assertOutcome(outcome, AppErrorCode.KIS_BUSINESS_ERROR.name, retryable = false)
        val row = rows().single()
        assertRow(row, ApiErrorType.KIS_BUSINESS_ERROR, retryable = false, operation = "KIS_DAILY_PRICE")
        assertEquals("TEST0001", row.businessCode)
    }

    @Test
    fun providerInvalidSymbolRejection_isKisBusinessError_nonRetryable() = runBlocking {
        seedLatestBar()
        enqueue(200, """{"rt_cd":"1","msg_cd":"TEST0002","msg1":"TEST invalid symbol"}""")
        val outcome = sync()
        assertOutcome(outcome, AppErrorCode.KIS_BUSINESS_ERROR.name, retryable = false)
        assertRow(rows().single(), ApiErrorType.KIS_BUSINESS_ERROR, retryable = false, operation = "KIS_DAILY_PRICE")
    }

    @Test
    fun providerMalformedResponse_isMalformed_nonRetryable_singleRow() = runBlocking {
        seedLatestBar()
        enqueue(200, """{"msg_cd":"TEST0003"}""")
        val outcome = sync()
        assertOutcome(outcome, AppErrorCode.KIS_MALFORMED_RESPONSE.name, retryable = false)
        assertRow(rows().single(), ApiErrorType.MALFORMED_RESPONSE, retryable = false, operation = "KIS_DAILY_PRICE")
    }

    @Test
    fun mappingFailure_isMalformed_nonRetryable_recordedOnceByGateway() = runBlocking {
        seedLatestBar()
        enqueue(200, dailyJson(THROUGH, close = "N/A"))
        val outcome = sync()
        assertOutcome(outcome, AppErrorCode.KIS_MALFORMED_RESPONSE.name, retryable = false)
        assertRow(rows().single(), ApiErrorType.MALFORMED_RESPONSE, retryable = false, operation = "KIS_FORWARD_SYNC")
    }

    @Test
    fun localInvalidSymbol_isLocalInvariant_nonRetryable_noHttpRequest() = runBlocking {
        val invalid = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "12AB", name = "Invalid"),
        )
        val outcome = gateway.syncUniverseTo(listOf(invalid), THROUGH)
        assertOutcome(outcome, AppErrorCode.INTERNAL_INVARIANT_VIOLATION.name, retryable = false)
        assertEquals(0, server.requestCount)
        assertRow(rows().single(), ApiErrorType.LOCAL_INVARIANT, retryable = false, operation = "KIS_FORWARD_SYNC")
    }

    @Test
    fun localInvalidDateRange_isLocalInvariant_nonRetryable_noHttpRequest() = runBlocking {
        seedBar(TODAY)
        val outcome = gateway.syncUniverseTo(listOf(instrumentId), TODAY.plusDays(2))
        assertOutcome(outcome, AppErrorCode.INTERNAL_INVARIANT_VIOLATION.name, retryable = false)
        assertEquals(0, server.requestCount)
        assertRow(rows().single(), ApiErrorType.LOCAL_INVARIANT, retryable = false, operation = "KIS_FORWARD_SYNC")
    }

    @Test
    fun networkTimeout_remainsRetryable_singleRow() = runBlocking {
        gateway = gateway(callTimeoutMillis = 300)
        seedLatestBar()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val outcome = sync()
        assertOutcome(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertRow(rows().single(), ApiErrorType.NETWORK_TIMEOUT, retryable = true, operation = "KIS_DAILY_PRICE")
    }

    @Test
    fun networkUnavailable_remainsRetryable_singleRow() = runBlocking {
        seedLatestBar()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val outcome = sync()
        assertOutcome(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertRow(rows().single(), ApiErrorType.HTTP_ERROR, retryable = true, operation = "KIS_DAILY_PRICE")
    }

    @Test
    fun http5xx_remainsRetryable_singleRow() = runBlocking {
        seedLatestBar()
        enqueue(503, "<html>unavailable</html>")
        val outcome = sync()
        assertOutcome(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        val row = rows().single()
        assertRow(row, ApiErrorType.HTTP_ERROR, retryable = true, operation = "KIS_DAILY_PRICE")
        assertEquals(503, row.httpStatus)
    }

    @Test
    fun rateLimit_boundedRetryUnchanged_oneRateLimitRowPerAttempt() = runBlocking {
        seedLatestBar()
        repeat(KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS) { enqueue(500, RATE_LIMIT_BODY) }
        val outcome = sync()
        assertOutcome(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals(KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS, server.requestCount)
        assertEquals(
            List(KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS - 1) { KisRequestPolicy.RATE_LIMIT_WAIT_MILLIS },
            sleeps,
        )
        assertEquals(61_000L, KisRequestPolicy.RATE_LIMIT_WAIT_MILLIS)
        val all = rows()
        assertEquals(KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS, all.size)
        all.forEach {
            assertRow(it, ApiErrorType.RATE_LIMIT, retryable = true, operation = "KIS_DAILY_PRICE")
            assertEquals("EGW00201", it.businessCode)
        }
    }

    @Test
    fun credentialRejection_isAuth_nonRetryable_singleRow() = runBlocking {
        seedLatestBar()
        enqueue(401, """{"rt_cd":"1","msg_cd":"TEST0401","msg1":"TEST expired"}""")
        val outcome = sync()
        assertOutcome(outcome, ForwardErrorCode.AUTH_REQUIRED.name, retryable = false)
        val row = rows().single()
        assertRow(row, ApiErrorType.AUTH_ERROR, retryable = false, operation = "KIS_DAILY_PRICE")
        assertEquals(401, row.httpStatus)
    }

    @Test
    fun prepareHistoryProviderFailure_writesExactlyOneRow() = runBlocking {
        enqueue(503, "<html>unavailable</html>")
        val outcome = gateway.prepareHistory(listOf(instrumentId), THROUGH)
        assertFalse(outcome.success)
        assertEquals("연결 실패", outcome.errorMessage)
        assertEquals(1, server.requestCount)
        val row = rows().single()
        assertRow(row, ApiErrorType.HTTP_ERROR, retryable = true, operation = "KIS_DAILY_PRICE")
        assertEquals(503, row.httpStatus)
    }

    @Test
    fun secretBearingProviderText_isSanitizedInPersistedRows() = runBlocking {
        seedLatestBar()
        enqueue(
            200,
            """{"rt_cd":"1","msg_cd":"TEST0004","msg1":"appsecret TEST_APP_SECRET authorization Bearer TEST_ACCESS_TOKEN"}""",
        )
        sync()
        val row = rows().single()
        assertEquals("secure error details omitted", row.safeMessage)
        val joined = listOf(row.operation, row.safeMessage, row.businessCode.orEmpty()).joinToString(" ")
        SECRET_VALUES.forEach { assertFalse("leaked $it", joined.contains(it)) }
    }

    @Test
    fun successfulSync_unchanged_noRows() = runBlocking {
        seedLatestBar()
        enqueue(200, dailyJson(THROUGH, close = "70500"))
        val outcome = sync()
        assertEquals(MarketSyncOutcome(success = true), outcome)
        assertEquals(THROUGH, localRepository.findLatest(instrumentId)?.tradeDate)
        assertEquals(70_500L, localRepository.findLatest(instrumentId)?.closePrice)
        assertTrue(rows().isEmpty())
    }

    private suspend fun sync(): MarketSyncOutcome = gateway.syncUniverseTo(listOf(instrumentId), THROUGH)

    private suspend fun rows(): List<ApiErrorLogEntity> =
        database.apiErrorLogDao().findSince(Instant.EPOCH)

    private suspend fun seedLatestBar() = seedBar(THROUGH.minusDays(1))

    private suspend fun seedBar(date: LocalDate) {
        val instrument = localRepository.findInstrumentById(instrumentId)!!
        localRepository.persistDailyBars(
            instrument,
            listOf(
                DailyStockBar(
                    symbol = "005930",
                    tradeDate = date,
                    openPrice = 70_000,
                    highPrice = 70_000,
                    lowPrice = 70_000,
                    closePrice = 70_000,
                    volume = 1,
                    tradingValue = 70_000,
                ),
            ),
            KisPriceAdjustment.ADJUSTED,
        )
    }

    private fun assertOutcome(outcome: MarketSyncOutcome, code: String, retryable: Boolean) {
        assertFalse(outcome.success)
        assertEquals(code, outcome.errorCode)
        assertEquals(retryable, outcome.retryable)
    }

    private fun assertRow(
        row: ApiErrorLogEntity,
        type: ApiErrorType,
        retryable: Boolean,
        operation: String,
    ) {
        assertEquals(type, row.errorType)
        assertEquals(retryable, row.retryable)
        assertEquals(operation, row.operation)
        assertEquals(NOW, row.occurredAt)
        assertNull(row.strategyRunId)
        assertNull(row.forwardCycleId)
        assertNull(row.operationId)
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private suspend fun gateway(callTimeoutMillis: Long = 5_000): KisForwardMarketDataGateway {
        val store = InMemoryKisSecretStore()
        store.saveCredentials(KisEnvironment.PRODUCTION, "TEST_APP_KEY", "TEST_APP_SECRET")
        store.saveToken(
            KisEnvironment.PRODUCTION,
            KisToken(
                accessToken = "TEST_ACCESS_TOKEN",
                tokenType = "Bearer",
                expiresAtEpochMillis = TOKEN_CLOCK + TimeUnit.HOURS.toMillis(1),
            ),
        )
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(KisReadOnlyInterceptor())
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val logger = RecordingKisAuthLogger()
        val auth = KisAuthRepository(
            api = retrofit.create(KisAuthApi::class.java),
            credentialStore = store,
            tokenStore = store,
            settingsStore = store,
            logger = logger,
            currentTimeMillis = { TOKEN_CLOCK },
            tokenUrl = { server.url("/oauth2/tokenP").toString() },
        )
        val apiErrorLog = ApiErrorLogService(database.apiErrorLogDao()) { NOW }
        val marketRepository = KisMarketRepositoryImpl(
            api = retrofit.create(KisMarketApi::class.java),
            authRepository = auth,
            credentialStore = store,
            logger = logger,
            apiErrorLog = apiErrorLog,
            today = { TODAY },
            baseUrl = { server.url("/").toString().trimEnd('/') },
        )
        localRepository = MarketDataLocalRepository(
            database = database,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            now = { NOW },
        )
        val historical = SyncHistoricalDailyBarsUseCase(
            marketRepository = marketRepository,
            localRepository = localRepository,
            environment = { KisEnvironment.PRODUCTION },
            sleep = { sleeps += it },
        )
        return KisForwardMarketDataGateway(
            credentials = store,
            settings = store,
            localRepository = localRepository,
            syncFromLatest = SyncDailyBarsFromLatestUseCase(localRepository, historical),
            historicalSync = historical,
            apiErrorLog = apiErrorLog,
        )
    }

    private fun dailyJson(date: LocalDate, close: String): String {
        val day = "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
        return """
            {
              "rt_cd":"0",
              "msg_cd":"MCA00000",
              "msg1":"TEST success",
              "output2":[
                {
                  "stck_bsop_date":"$day",
                  "stck_oprc":"70000",
                  "stck_hgpr":"71000",
                  "stck_lwpr":"69000",
                  "stck_clpr":"$close",
                  "acml_vol":"100",
                  "acml_tr_pbmn":"1"
                }
              ]
            }
        """.trimIndent()
    }

    private companion object {
        const val TOKEN_CLOCK = 1_700_000_000_000L
        val NOW: Instant = Instant.parse("2026-09-18T06:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 18)
        val THROUGH: LocalDate = TODAY
        const val RATE_LIMIT_BODY =
            """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"TEST rate limited"}"""
        val SECRET_VALUES = listOf("TEST_APP_KEY", "TEST_APP_SECRET", "TEST_ACCESS_TOKEN")
    }
}
