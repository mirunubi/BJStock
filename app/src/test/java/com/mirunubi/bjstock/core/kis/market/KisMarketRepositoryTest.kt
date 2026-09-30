package com.mirunubi.bjstock.core.kis.market

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.InMemoryApiErrorLogDao
import com.mirunubi.bjstock.core.audit.KisApiErrorMapper
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.SafeAppError
import com.mirunubi.bjstock.core.kis.InMemoryKisSecretStore
import com.mirunubi.bjstock.core.kis.KisAuthErrorKind
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisToken
import com.mirunubi.bjstock.core.kis.RecordingKisAuthLogger
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisCurrentPriceResponseDto
import com.mirunubi.bjstock.core.network.kis.KisDailyChartResponseDto
import com.mirunubi.bjstock.core.network.kis.KisMarketApi
import com.mirunubi.bjstock.core.network.kis.KisReadOnlyInterceptor
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
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

    @Test
    fun dailyBars_http500WithEgw00201_isRateLimitedWithParsedBody() = runBlocking {
        enqueueError(500, RATE_LIMIT_BODY)
        val error = runCatching { dailyBars(repository()) }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.RATE_LIMITED, (error as KisMarketException).kind)
        assertEquals("EGW00201", error.audit?.msgCd)
        assertEquals("1", error.audit?.rtCd)
        assertEquals(500, error.audit?.httpCode)
        assertEquals("초당 거래건수를 초과하였습니다.", error.audit?.msg1)
        assertEquals(1, server.requestCount)
        assertTrue(logger.messages.any { it.contains("500") && it.contains("EGW00201") })
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun dailyBars_http200RtCdNotZeroWithEgw00201_isRateLimited() = runBlocking {
        enqueueJson(RATE_LIMIT_BODY)
        val error = runCatching { dailyBars(repository()) }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.RATE_LIMITED, (error as KisMarketException).kind)
        assertEquals("EGW00201", error.audit?.msgCd)
    }

    @Test
    fun dailyBars_http500WithOtherMsgCd_staysGenericHttpWithBusinessCode() = runBlocking {
        enqueueError(500, """{"rt_cd":"1","msg_cd":"EGW00500","msg1":"TEST server error"}""")
        val error = runCatching { dailyBars(repository()) }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.HTTP, (error as KisMarketException).kind)
        assertEquals("연결 실패", error.publicMessage)
        assertEquals("EGW00500", error.audit?.msgCd)
        assertEquals(500, error.audit?.httpCode)
    }

    @Test
    fun dailyBars_http500MalformedBody_preservesGenericHttpError() = runBlocking {
        val bodies = listOf(
            "",
            "error",
            "<html><body>Internal Server Error</body></html>",
            "{\"rt_cd\":\"1\",\"msg_cd\":",
            "[\"EGW00201\"]",
            "\"EGW00201\"",
            "{\"msg_cd\":{\"nested\":true}}",
            "x".repeat(10_000),
        )
        bodies.forEach { body ->
            enqueueError(500, body)
            val error = runCatching { dailyBars(repository()) }.exceptionOrNull()
            assertEquals(body.take(40), KisMarketErrorKind.HTTP, (error as KisMarketException).kind)
            assertEquals("연결 실패", error.publicMessage)
            assertEquals(500, error.audit?.httpCode)
            assertNull(error.audit?.msgCd)
        }
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun dailyBars_http401WithBody_staysAuthentication() = runBlocking {
        enqueueError(401, """{"rt_cd":"1","msg_cd":"EGW00123","msg1":"TEST expired"}""")
        val error = runCatching { dailyBars(repository()) }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.AUTHENTICATION, (error as KisMarketException).kind)
        assertEquals(401, error.audit?.httpCode)
    }

    @Test
    fun apiErrorLog_recordsMsgCdAsBusinessCodeWithSafeMessageOnly() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        enqueueError(500, RATE_LIMIT_BODY)
        runCatching { dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger))) }
        val row = dao.rows.single()
        assertEquals("KIS_DAILY_PRICE", row.operation)
        assertEquals(ApiErrorType.RATE_LIMIT, row.errorType)
        assertEquals(500, row.httpStatus)
        assertEquals("EGW00201", row.businessCode)
        assertTrue(row.retryable)
        assertEquals("KIS 요청 한도 초과: 초당 거래건수를 초과하였습니다.", row.safeMessage)
        assertRowHasNoSecrets(row)

        enqueueError(500, """{"rt_cd":"1","msg_cd":"EGW00500"}""")
        runCatching { dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger))) }
        val generic = dao.rows.last()
        assertEquals(ApiErrorType.HTTP_ERROR, generic.errorType)
        assertEquals("EGW00500", generic.businessCode)
        assertEquals("연결 실패", generic.safeMessage)
        assertRowHasNoSecrets(generic)
    }

    @Test
    fun apiErrorLog_secretLikeMsg1_isOmitted() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        enqueueError(
            500,
            """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"appsecret TEST_APP_SECRET authorization Bearer TEST_ACCESS_TOKEN"}""",
        )
        runCatching { dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger))) }
        val row = dao.rows.single()
        assertEquals("EGW00201", row.businessCode)
        assertEquals("secure error details omitted", row.safeMessage)
        assertRowHasNoSecrets(row)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun unexpectedLocalDefect_isUnexpectedNotMalformed() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        val api = ThrowingKisMarketApi(IllegalArgumentException("appsecret TEST_APP_SECRET raw"))
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger), marketApi = api))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.UNEXPECTED, error.kind)
        assertEquals("예상치 못한 오류", error.publicMessage)
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, AppErrorMapper.fromKisMarketException(error))
        assertFalse(SafeAppError.fromThrowable(error).code.isRetryableAutomatically)
        val row = dao.rows.single()
        assertEquals("KIS_DAILY_PRICE", row.operation)
        assertEquals(ApiErrorType.UNEXPECTED, row.errorType)
        assertFalse(row.retryable)
        assertEquals("예상치 못한 오류", row.safeMessage)
        assertFalse(row.safeMessage.contains("raw"))
        assertRowHasNoSecrets(row)
    }

    @Test
    fun readOnlyGuardIllegalState_isRethrownUnrecorded() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        val guard = IllegalStateException("KIS trading endpoint is prohibited in current BJStock phase")
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger), marketApi = ThrowingKisMarketApi(guard)))
        }.exceptionOrNull()
        assertTrue(error === guard)
        assertTrue(dao.rows.isEmpty())
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, SafeAppError.fromThrowable(error!!).code)
    }

    @Test
    fun marketCallCancellation_isRethrownUnrecorded() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        val cancel = CancellationException("stopped")
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger), marketApi = ThrowingKisMarketApi(cancel)))
        }.exceptionOrNull()
        assertTrue(error === cancel)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun providerMalformed_staysMalformedResponse() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        val repo = repository(apiErrorLog = ApiErrorLogService(dao, logger))
        enqueueJson("{not-json")
        val parse = runCatching { dailyBars(repo) }.exceptionOrNull() as KisMarketException
        enqueueJson("""{"msg_cd":"MCA00000"}""")
        val noRtCd = runCatching { dailyBars(repo) }.exceptionOrNull() as KisMarketException
        listOf(parse, noRtCd).forEach {
            assertEquals(KisMarketErrorKind.MALFORMED_RESPONSE, it.kind)
            assertEquals(AppErrorCode.KIS_MALFORMED_RESPONSE, AppErrorMapper.fromKisMarketException(it))
        }
        assertEquals(2, dao.rows.size)
        dao.rows.forEach {
            assertEquals(ApiErrorType.MALFORMED_RESPONSE, it.errorType)
            assertFalse(it.retryable)
        }
    }

    @Test
    fun mappingFailure_staysCanonicalMalformed() = runBlocking {
        enqueueJson(
            """
            {"rt_cd":"0","msg_cd":"MCA00000","output2":[{"stck_bsop_date":"20260916",
            "stck_oprc":"abc","stck_hgpr":"110","stck_lwpr":"90","stck_clpr":"105","acml_vol":"1"}]}
            """.trimIndent(),
        )
        val error = runCatching { dailyBars(repository()) }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.MAPPING_FAILURE, error.kind)
        assertEquals(AppErrorCode.KIS_MALFORMED_RESPONSE, AppErrorMapper.fromKisMarketException(error))
        assertEquals(ApiErrorType.MALFORMED_RESPONSE, KisApiErrorMapper.fromMarketKind(error.kind))
    }

    @Test
    fun businessRejection_semanticsUnchanged() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        enqueueJson("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"TEST business error"}""")
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger)))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.BUSINESS, error.kind)
        assertEquals(AppErrorCode.KIS_BUSINESS_ERROR, AppErrorMapper.fromKisMarketException(error))
        val row = dao.rows.single()
        assertEquals(ApiErrorType.KIS_BUSINESS_ERROR, row.errorType)
        assertEquals("EGW00123", row.businessCode)
        assertFalse(row.retryable)
    }

    @Test
    fun tokenTimeout_isNetworkTimeoutWithOneOauthRow() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        expireCachedToken()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = runCatching {
            dailyBars(repository(callTimeoutMillis = 300, apiErrorLog = ApiErrorLogService(dao, logger)))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisAuthErrorKind.NETWORK_TIMEOUT, error.authKind)
        assertEquals(AppErrorCode.NETWORK_TIMEOUT, AppErrorMapper.fromKisMarketException(error))
        assertEquals(AppErrorCode.NETWORK_TIMEOUT, SafeAppError.fromThrowable(error).code)
        assertEquals("인증 토큰 발급 실패", error.publicMessage)
        val row = dao.rows.single()
        assertEquals("KIS_OAUTH", row.operation)
        assertEquals(ApiErrorType.NETWORK_TIMEOUT, row.errorType)
        assertTrue(row.retryable)
        assertEquals(1, server.requestCount)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun tokenRejected_isCredentialRejectedWithOneOauthRow() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        expireCachedToken()
        enqueueError(403, """{"error_code":"EGW00002"}""")
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger)))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.AUTHENTICATION, error.kind)
        assertEquals(KisAuthErrorKind.CREDENTIAL_REJECTED, error.authKind)
        assertEquals(AppErrorCode.CREDENTIAL_REJECTED, AppErrorMapper.fromKisMarketException(error))
        assertEquals("인증 필요", error.publicMessage)
        assertEquals(403, error.audit?.httpCode)
        val row = dao.rows.single()
        assertEquals("KIS_OAUTH", row.operation)
        assertEquals(ApiErrorType.AUTH_ERROR, row.errorType)
        assertFalse(row.retryable)
        assertEquals(403, row.httpStatus)
    }

    @Test
    fun missingCredentials_isCredentialMissingWithOneRow() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        store.deleteToken(KisEnvironment.PRODUCTION)
        store.deleteCredentials(KisEnvironment.PRODUCTION)
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger)))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisAuthErrorKind.CREDENTIAL_MISSING, error.authKind)
        assertEquals(AppErrorCode.CREDENTIAL_MISSING, AppErrorMapper.fromKisMarketException(error))
        assertEquals("인증 필요", error.publicMessage)
        val row = dao.rows.single()
        assertEquals("KIS_DAILY_PRICE", row.operation)
        assertEquals(ApiErrorType.AUTH_ERROR, row.errorType)
        assertFalse(row.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun marketHttp401_staysAuthRequiredSingleRow() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        enqueueError(401, """{"rt_cd":"1","msg_cd":"EGW00123"}""")
        val error = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger)))
        }.exceptionOrNull() as KisMarketException
        assertNull(error.authKind)
        assertEquals(AppErrorCode.AUTH_REQUIRED, AppErrorMapper.fromKisMarketException(error))
        val row = dao.rows.single()
        assertEquals("KIS_DAILY_PRICE", row.operation)
        assertEquals(ApiErrorType.AUTH_ERROR, row.errorType)
        assertFalse(row.retryable)
    }

    @Test
    fun evidenceWriteFailure_preservesMarketFailureSemantics() = runBlocking {
        val dao = InMemoryApiErrorLogDao().apply { insertFailure = IllegalStateException(SECRET_BEARING_SQL) }
        val repo = repository(apiErrorLog = ApiErrorLogService(dao, logger))

        enqueueJson("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"TEST business error"}""")
        val business = runCatching { dailyBars(repo) }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.BUSINESS, business.kind)
        assertEquals("KIS 응답 오류", business.publicMessage)
        assertEquals("EGW00123", business.audit?.msgCd)
        assertEquals(AppErrorCode.KIS_BUSINESS_ERROR, AppErrorMapper.fromKisMarketException(business))

        val network = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger), marketApi = ThrowingKisMarketApi(IOException("x"))))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.HTTP, network.kind)
        assertEquals("연결 실패", network.publicMessage)
        assertTrue(KisApiErrorMapper.isRetryable(network.kind))
        assertTrue(AppErrorMapper.fromKisMarketException(network).isRetryableAutomatically)

        enqueueJson("{not-json")
        val malformed = runCatching { dailyBars(repo) }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.MALFORMED_RESPONSE, malformed.kind)
        assertEquals(AppErrorCode.KIS_MALFORMED_RESPONSE, AppErrorMapper.fromKisMarketException(malformed))

        val unexpected = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger), marketApi = ThrowingKisMarketApi(IllegalArgumentException("x"))))
        }.exceptionOrNull() as KisMarketException
        assertEquals(KisMarketErrorKind.UNEXPECTED, unexpected.kind)
        assertEquals("예상치 못한 오류", unexpected.publicMessage)

        assertTrue(dao.rows.isEmpty())
        assertEquals(4, dao.insertAttempts)
        assertEquals(
            List(4) { "API error evidence write failed: KIS_DAILY_PRICE (IllegalStateException)" },
            logger.messages.filter { it.startsWith("API error evidence write failed") },
        )
        assertTrue(logger.messages.none { it.contains("INSERT") || it.contains("api_error_logs") })
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun evidenceWriteCancellation_isRethrown() = runBlocking {
        val dao = InMemoryApiErrorLogDao().apply { insertFailure = CancellationException("stopped") }
        enqueueJson("""{"rt_cd":"1","msg_cd":"EGW00123"}""")
        val thrown = runCatching {
            dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger)))
        }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is CancellationException)
        assertTrue(logger.messages.none { it.startsWith("API error evidence write failed") })
    }

    @Test
    fun success_recordsNoApiErrorRow() = runBlocking {
        val dao = InMemoryApiErrorLogDao()
        enqueueJson(dailyJsonNewestFirstWithDuplicate())
        val bars = dailyBars(repository(apiErrorLog = ApiErrorLogService(dao, logger)))
        assertEquals(3, bars.size)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun rateLimitedKind_mapsToRateLimitTypeAndIsRetryable() {
        assertEquals(
            ApiErrorType.RATE_LIMIT,
            KisApiErrorMapper.fromMarketKind(KisMarketErrorKind.RATE_LIMITED),
        )
        assertTrue(KisApiErrorMapper.isRetryable(KisMarketErrorKind.RATE_LIMITED))
        assertTrue(KisRequestPolicy.isRateLimit("EGW00201"))
        assertFalse(KisRequestPolicy.isRateLimit("EGW00500"))
        assertFalse(KisRequestPolicy.isRateLimit(null))
    }

    private suspend fun expireCachedToken() {
        store.saveToken(
            KisEnvironment.PRODUCTION,
            KisToken(
                accessToken = "TEST_ACCESS_TOKEN",
                tokenType = "Bearer",
                expiresAtEpochMillis = NOW,
            ),
        )
    }

    private suspend fun dailyBars(repository: KisMarketRepositoryImpl) = repository.inquireDailyBars(
        symbol = "005930",
        startDate = LocalDate.of(2026, 9, 16),
        endDate = LocalDate.of(2026, 9, 18),
    )

    private fun assertRowHasNoSecrets(row: ApiErrorLogEntity) {
        val joined = listOf(row.operation, row.safeMessage, row.businessCode.orEmpty()).joinToString(" ")
        SECRET_VALUES.forEach { secret -> assertFalse(joined.contains(secret)) }
    }

    private fun enqueueError(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun repository(
        callTimeoutMillis: Long = 5_000,
        apiErrorLog: ApiErrorLogService? = null,
        marketApi: KisMarketApi? = null,
    ): KisMarketRepositoryImpl {
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
            apiErrorLog = apiErrorLog,
            currentTimeMillis = { NOW },
            tokenUrl = { server.url("/oauth2/tokenP").toString() },
        )
        return KisMarketRepositoryImpl(
            api = marketApi ?: retrofit.create(KisMarketApi::class.java),
            authRepository = auth,
            credentialStore = store,
            logger = logger,
            apiErrorLog = apiErrorLog,
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
        private const val SECRET_BEARING_SQL =
            "INSERT INTO api_error_logs failed near appsecret TEST_APP_SECRET"
        private const val RATE_LIMIT_BODY =
            """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}"""
        private val SECRET_VALUES = listOf(
            "TEST_APP_KEY",
            "TEST_APP_SECRET",
            "TEST_ACCESS_TOKEN",
        )
    }
}

private class ThrowingKisMarketApi(private val failure: Throwable) : KisMarketApi {
    override suspend fun inquirePrice(
        url: String,
        headers: Map<String, String>,
        marketDivision: String,
        symbol: String,
    ): KisCurrentPriceResponseDto = throw failure

    override suspend fun inquireDailyItemChartPrice(
        url: String,
        headers: Map<String, String>,
        marketDivision: String,
        symbol: String,
        startDate: String,
        endDate: String,
        period: String,
        adjustment: String,
    ): KisDailyChartResponseDto = throw failure
}