package com.mirunubi.bjstock.core.kis

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.InMemoryApiErrorLogDao
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.SafeAppError
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import com.mirunubi.bjstock.core.network.kis.KisTokenRequestDto
import com.mirunubi.bjstock.core.network.kis.KisTokenResponseDto
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class KisAuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryKisSecretStore
    private lateinit var logger: RecordingKisAuthLogger
    private lateinit var apiErrors: InMemoryApiErrorLogDao
    private var nowMillis = NOW

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryKisSecretStore()
        logger = RecordingKisAuthLogger()
        apiErrors = InMemoryApiErrorLogDao()
        nowMillis = NOW
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun tokenRequest_bodyUsesOfficialFieldNames() = runBlocking {
        enqueueSuccess()
        saveProductionCredentials()
        repository().getValidToken(KisEnvironment.PRODUCTION)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/oauth2/tokenP"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("client_credentials", body.getValue("grant_type").jsonPrimitive.content)
        assertEquals("TEST_APP_KEY", body.getValue("appkey").jsonPrimitive.content)
        assertEquals("TEST_APP_SECRET", body.getValue("appsecret").jsonPrimitive.content)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun tokenRequest_success200() = runBlocking {
        enqueueSuccess()
        saveProductionCredentials()
        val token = repository().getValidToken(KisEnvironment.PRODUCTION)
        assertEquals("TEST_ACCESS_TOKEN", token.accessToken)
        assertEquals("Bearer", token.tokenType)
        assertEquals(NOW + TimeUnit.SECONDS.toMillis(86_400), token.expiresAtEpochMillis)
        assertEquals(KisAuthState.AUTHENTICATED, repository().refreshState(KisEnvironment.PRODUCTION))
        logger.assertNoSecrets(SECRET_VALUES)
        assertTrue(logger.messages.any { it == "KIS token request success" })
    }

    @Test
    fun tokenRequest_unauthorized401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid"}"""))
        saveProductionCredentials()
        val error = runCatching { repository().getValidToken(KisEnvironment.PRODUCTION) }.exceptionOrNull()
        assertTrue(error is KisAuthException)
        assertEquals(401, (error as KisAuthException).httpCode)
        logger.assertNoSecrets(SECRET_VALUES)
        assertTrue(logger.messages.any { it.contains("HTTP 401") })
    }

    @Test
    fun tokenRequest_serverError500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        saveProductionCredentials()
        val error = runCatching { repository().getValidToken(KisEnvironment.PRODUCTION) }.exceptionOrNull()
        assertTrue(error is KisAuthException)
        assertEquals(500, (error as KisAuthException).httpCode)
        logger.assertNoSecrets(SECRET_VALUES)
        assertTrue(logger.messages.any { it.contains("HTTP 500") })
    }

    @Test
    fun tokenRequest_malformedResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"not":"a-token"}"""),
        )
        saveProductionCredentials()
        val error = runCatching { repository().getValidToken(KisEnvironment.PRODUCTION) }.exceptionOrNull()
        assertTrue(error is KisAuthException)
        assertTrue((error as KisAuthException).publicMessage.contains("malformed"))
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun tokenRequest_networkTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        saveProductionCredentials()
        val error = runCatching {
            repository(callTimeoutMillis = 300).getValidToken(KisEnvironment.PRODUCTION)
        }.exceptionOrNull()
        assertTrue(error is KisAuthException)
        assertTrue((error as KisAuthException).publicMessage.contains("network"))
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun missingCredentials_isCredentialMissingWithoutRow() = runBlocking {
        val error = tokenFailure()
        assertEquals(KisAuthErrorKind.CREDENTIAL_MISSING, error.kind)
        assertEquals(AppErrorCode.CREDENTIAL_MISSING, SafeAppError.fromThrowable(error).code)
        assertEquals(0, server.requestCount)
        assertTrue(apiErrors.rows.isEmpty())
        assertEquals(KisAuthState.NOT_CONFIGURED, repository().testConnection(KisEnvironment.PRODUCTION))
    }

    @Test
    fun http401And403_areCredentialRejected() = runBlocking {
        saveProductionCredentials()
        listOf(401, 403).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"invalid"}"""))
            val error = tokenFailure()
            assertEquals(KisAuthErrorKind.CREDENTIAL_REJECTED, error.kind)
            assertEquals(code, error.httpCode)
            assertEquals(AppErrorCode.CREDENTIAL_REJECTED, SafeAppError.fromThrowable(error).code)
            assertRow(apiErrors.rows.last(), ApiErrorType.AUTH_ERROR, retryable = false, httpStatus = code)
        }
        assertEquals(2, apiErrors.rows.size)
    }

    @Test
    fun http5xx_isServerErrorRetryable() = runBlocking {
        saveProductionCredentials()
        listOf(500, 503).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("error"))
            val error = tokenFailure()
            assertEquals(KisAuthErrorKind.SERVER_ERROR, error.kind)
            val app = SafeAppError.fromThrowable(error)
            assertEquals(AppErrorCode.KIS_SERVER_ERROR, app.code)
            assertTrue(app.code.isRetryableAutomatically)
            assertEquals(code, app.diagnostics.httpStatus)
            assertRow(apiErrors.rows.last(), ApiErrorType.HTTP_ERROR, retryable = true, httpStatus = code)
        }
    }

    @Test
    fun otherHttp_isAuthRequired() = runBlocking {
        saveProductionCredentials()
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        val error = tokenFailure()
        assertEquals(KisAuthErrorKind.AUTH_REQUIRED, error.kind)
        assertEquals(AppErrorCode.AUTH_REQUIRED, SafeAppError.fromThrowable(error).code)
        assertRow(apiErrors.rows.single(), ApiErrorType.AUTH_ERROR, retryable = false, httpStatus = 400)
    }

    @Test
    fun malformedToken_isKisMalformedResponse() = runBlocking {
        saveProductionCredentials()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"not":"a-token"}"""),
        )
        val error = tokenFailure()
        assertEquals(KisAuthErrorKind.MALFORMED_RESPONSE, error.kind)
        assertEquals(AppErrorCode.KIS_MALFORMED_RESPONSE, SafeAppError.fromThrowable(error).code)
        assertRow(apiErrors.rows.single(), ApiErrorType.MALFORMED_RESPONSE, retryable = false)
    }

    @Test
    fun socketTimeout_isNetworkTimeout() = runBlocking {
        saveProductionCredentials()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val real = tokenFailure(callTimeoutMillis = 300)
        val socket = tokenFailure(authApi = FailingKisAuthApi(SocketTimeoutException("t")))
        listOf(real, socket).forEach { error ->
            assertEquals(KisAuthErrorKind.NETWORK_TIMEOUT, error.kind)
            assertEquals(AppErrorCode.NETWORK_TIMEOUT, SafeAppError.fromThrowable(error).code)
        }
        assertEquals(2, apiErrors.rows.size)
        apiErrors.rows.forEach { assertRow(it, ApiErrorType.NETWORK_TIMEOUT, retryable = true) }
    }

    @Test
    fun otherIoException_isNetworkUnavailableNotAuthRequired() = runBlocking {
        saveProductionCredentials()
        val error = tokenFailure(authApi = FailingKisAuthApi(IOException("unreachable host")))
        assertEquals(KisAuthErrorKind.NETWORK_UNAVAILABLE, error.kind)
        val code = SafeAppError.fromThrowable(error).code
        assertEquals(AppErrorCode.NETWORK_UNAVAILABLE, code)
        assertNotEquals(AppErrorCode.AUTH_REQUIRED, code)
        assertTrue(code.isRetryableAutomatically)
        assertRow(apiErrors.rows.single(), ApiErrorType.NETWORK_TIMEOUT, retryable = true)
        assertEquals(KisAuthState.ERROR, repository(authApi = FailingKisAuthApi(IOException("x")))
            .testConnection(KisEnvironment.PRODUCTION))
    }

    @Test
    fun unexpectedFailure_isUnexpectedWithoutRawMessage() = runBlocking {
        saveProductionCredentials()
        val raw = "raw-defect-detail TEST_APP_SECRET"
        val error = tokenFailure(authApi = FailingKisAuthApi(IllegalArgumentException(raw)))
        assertEquals(KisAuthErrorKind.UNEXPECTED, error.kind)
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, SafeAppError.fromThrowable(error).code)
        val row = apiErrors.rows.single()
        assertRow(row, ApiErrorType.UNEXPECTED, retryable = false)
        assertEquals("KIS token request failed: unexpected error", row.safeMessage)
        listOf(error.publicMessage, error.message.orEmpty(), row.safeMessage).forEach { text ->
            assertFalse(text, text.contains("raw-defect-detail"))
            assertFalse(text, text.contains("TEST_APP_SECRET"))
        }
        assertTrue(logger.messages.none { it.contains("raw-defect-detail") })
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun cancellation_isRethrownWithoutRow() = runBlocking {
        saveProductionCredentials()
        val cancel = CancellationException("stopped")
        val thrown = runCatching {
            repository(authApi = FailingKisAuthApi(cancel)).getValidToken(KisEnvironment.PRODUCTION)
        }.exceptionOrNull()
        assertTrue(thrown === cancel)
        assertTrue(apiErrors.rows.isEmpty())
    }

    @Test
    fun testConnection_successIsAuthenticated() = runBlocking {
        enqueueSuccess()
        saveProductionCredentials()
        assertEquals(KisAuthState.AUTHENTICATED, repository().testConnection(KisEnvironment.PRODUCTION))
        assertTrue(apiErrors.rows.isEmpty())
    }

    @Test
    fun validToken_isReusedWithoutNetwork() = runBlocking {
        saveProductionCredentials()
        store.saveToken(
            KisEnvironment.PRODUCTION,
            KisToken(
                accessToken = "TEST_ACCESS_TOKEN",
                tokenType = "Bearer",
                expiresAtEpochMillis = NOW + TimeUnit.MINUTES.toMillis(10),
            ),
        )
        val token = repository().getValidToken(KisEnvironment.PRODUCTION)
        assertEquals("TEST_ACCESS_TOKEN", token.accessToken)
        assertEquals(0, server.requestCount)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun expiredToken_refreshesOnce() = runBlocking {
        enqueueSuccess(accessToken = "TEST_ACCESS_TOKEN_REFRESHED")
        saveProductionCredentials()
        store.saveToken(
            KisEnvironment.PRODUCTION,
            KisToken(
                accessToken = "TEST_ACCESS_TOKEN_OLD",
                tokenType = "Bearer",
                expiresAtEpochMillis = NOW + TimeUnit.MINUTES.toMillis(1),
            ),
        )
        val token = repository().getValidToken(KisEnvironment.PRODUCTION)
        assertEquals("TEST_ACCESS_TOKEN_REFRESHED", token.accessToken)
        assertEquals(1, server.requestCount)
        logger.assertNoSecrets(SECRET_VALUES + "TEST_ACCESS_TOKEN_OLD" + "TEST_ACCESS_TOKEN_REFRESHED")
    }

    @Test
    fun missingToken_requestsOnce() = runBlocking {
        enqueueSuccess()
        saveProductionCredentials()
        repository().getValidToken(KisEnvironment.PRODUCTION)
        assertEquals(1, server.requestCount)
        logger.assertNoSecrets(SECRET_VALUES)
    }

    @Test
    fun concurrentGetValidToken_issuesOneRequest() = runBlocking {
        repeat(5) { enqueueSuccess() }
        saveProductionCredentials()
        val auth = repository()
        val tokens = (1..5).map {
            async(Dispatchers.IO) { auth.getValidToken(KisEnvironment.PRODUCTION) }
        }.awaitAll()
        assertEquals(1, server.requestCount)
        assertTrue(tokens.all { it.accessToken == "TEST_ACCESS_TOKEN" })
        logger.assertNoSecrets(SECRET_VALUES)
    }

    private suspend fun tokenFailure(
        callTimeoutMillis: Long = 5_000,
        authApi: KisAuthApi? = null,
    ): KisAuthException {
        val error = runCatching {
            repository(callTimeoutMillis, authApi).getValidToken(KisEnvironment.PRODUCTION)
        }.exceptionOrNull()
        assertTrue(error.toString(), error is KisAuthException)
        return error as KisAuthException
    }

    private fun assertRow(
        row: ApiErrorLogEntity,
        type: ApiErrorType,
        retryable: Boolean,
        httpStatus: Int? = null,
    ) {
        assertEquals("KIS_OAUTH", row.operation)
        assertEquals(type, row.errorType)
        assertEquals(retryable, row.retryable)
        assertEquals(httpStatus, row.httpStatus)
    }

    private suspend fun saveProductionCredentials() {
        store.saveCredentials(
            KisEnvironment.PRODUCTION,
            "TEST_APP_KEY",
            "TEST_APP_SECRET",
        )
    }

    private fun enqueueSuccess(accessToken: String = "TEST_ACCESS_TOKEN") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "$accessToken",
                      "token_type": "Bearer",
                      "expires_in": 86400,
                      "access_token_token_expired": "ignored"
                    }
                    """.trimIndent(),
                ),
        )
    }

    private fun repository(
        callTimeoutMillis: Long = 5_000,
        authApi: KisAuthApi? = null,
    ): KisAuthRepository {
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KisAuthApi::class.java)
        return KisAuthRepository(
            api = authApi ?: api,
            credentialStore = store,
            tokenStore = store,
            settingsStore = store,
            logger = logger,
            apiErrorLog = ApiErrorLogService(apiErrors),
            currentTimeMillis = { nowMillis },
            tokenUrl = { server.url("/oauth2/tokenP").toString() },
        )
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
        private val SECRET_VALUES = listOf(
            "TEST_APP_KEY",
            "TEST_APP_SECRET",
            "TEST_ACCESS_TOKEN",
            "Bearer ",
        )
    }
}

private class FailingKisAuthApi(private val failure: Throwable) : KisAuthApi {
    override suspend fun issueToken(url: String, request: KisTokenRequestDto): KisTokenResponseDto =
        throw failure
}
