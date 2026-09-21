package com.mirunubi.bjstock.core.kis

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mirunubi.bjstock.core.network.kis.KisAuthApi
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class KisAuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryKisSecretStore
    private lateinit var logger: RecordingKisAuthLogger
    private var nowMillis = NOW

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryKisSecretStore()
        logger = RecordingKisAuthLogger()
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

    private fun repository(callTimeoutMillis: Long = 5_000): KisAuthRepository {
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
            api = api,
            credentialStore = store,
            tokenStore = store,
            settingsStore = store,
            logger = logger,
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
