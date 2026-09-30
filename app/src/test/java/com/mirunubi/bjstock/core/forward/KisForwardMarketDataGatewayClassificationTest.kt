package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.ErrorCategory
import com.mirunubi.bjstock.core.kis.InMemoryKisSecretStore
import com.mirunubi.bjstock.core.kis.KisAuthErrorKind
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorAudit
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import com.mirunubi.bjstock.core.kis.market.KisRequestPolicy
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncErrorKind
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncException
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import com.mirunubi.bjstock.core.marketdata.SyncDailyBarsFromLatestUseCase
import com.mirunubi.bjstock.core.marketdata.SyncHistoricalDailyBarsUseCase
import com.mirunubi.bjstock.core.model.ApiErrorType
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KisForwardMarketDataGatewayClassificationTest {
    private lateinit var database: BJStockDatabase
    private lateinit var marketRepository: FailingKisMarketRepository
    private lateinit var localRepository: MarketDataLocalRepository
    private lateinit var secrets: InMemoryKisSecretStore
    private lateinit var gateway: KisForwardMarketDataGateway
    private var instrumentId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localRepository = MarketDataLocalRepository(
            database = database,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            now = { NOW },
        )
        marketRepository = FailingKisMarketRepository()
        val historical = SyncHistoricalDailyBarsUseCase(
            marketRepository = marketRepository,
            localRepository = localRepository,
            environment = { KisEnvironment.PRODUCTION },
            sleep = {},
        )
        secrets = InMemoryKisSecretStore()
        gateway = KisForwardMarketDataGateway(
            credentials = secrets,
            settings = secrets,
            localRepository = localRepository,
            syncFromLatest = SyncDailyBarsFromLatestUseCase(localRepository, historical),
            historicalSync = historical,
            apiErrorLog = ApiErrorLogService(database.apiErrorLogDao()) { NOW },
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun invalidDateRange_isInternalInvariantViolation_nonRetryable() = runBlocking {
        marketRepository.failure = HistoricalSyncException(
            HistoricalSyncErrorKind.INVALID_DATE_RANGE,
            "startDate must be on or before endDate",
        )
        val outcome = sync()
        assertFailure(outcome, AppErrorCode.INTERNAL_INVARIANT_VIOLATION.name, retryable = false)
        assertCanonical(outcome, AppErrorCode.INTERNAL_INVARIANT_VIOLATION, ErrorCategory.INVARIANT)
        val logged = apiErrors().single()
        assertFalse(logged.retryable)
        assertEquals(ApiErrorType.LOCAL_INVARIANT, logged.errorType)
    }

    @Test
    fun noLatestBar_isInternalInvariantViolation_nonRetryable() = runBlocking {
        insertBar(THROUGH.minusDays(3))
        marketRepository.failure = HistoricalSyncException(
            HistoricalSyncErrorKind.NO_LATEST_BAR,
            "Explicit startDate is required when no daily bars exist",
        )
        val outcome = sync()
        assertFailure(outcome, AppErrorCode.INTERNAL_INVARIANT_VIOLATION.name, retryable = false)
        assertCanonical(outcome, AppErrorCode.INTERNAL_INVARIANT_VIOLATION, ErrorCategory.INVARIANT)
        val logged = apiErrors().single()
        assertFalse(logged.retryable)
        assertEquals(ApiErrorType.LOCAL_INVARIANT, logged.errorType)
    }

    @Test
    fun unknownException_isUnexpectedException_nonRetryable() = runBlocking {
        marketRepository.failure = IllegalArgumentException("unmapped local failure")
        val outcome = sync()
        assertFailure(outcome, AppErrorCode.UNEXPECTED_EXCEPTION.name, retryable = false)
        assertCanonical(outcome, AppErrorCode.UNEXPECTED_EXCEPTION, ErrorCategory.UNEXPECTED)
        assertEquals("Unexpected error (IllegalArgumentException)", outcome.errorMessage)
        val logged = apiErrors().single()
        assertFalse(logged.retryable)
        assertEquals(ApiErrorType.UNEXPECTED, logged.errorType)
        assertEquals("Unexpected error (IllegalArgumentException)", logged.safeMessage)
    }

    @Test
    fun networkTimeout_remainsTransientRetryable() = runBlocking {
        marketRepository.failure = KisMarketException(KisMarketErrorKind.NETWORK_TIMEOUT, "연결 실패")
        val outcome = sync()
        assertFailure(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals("연결 실패", outcome.errorMessage)
        assertCanonical(outcome, AppErrorCode.NETWORK_UNAVAILABLE, ErrorCategory.TRANSIENT)
        assertTrue("repository records provider failures", apiErrors().isEmpty())

        marketRepository.failure = SocketTimeoutException("read timed out")
        val raw = sync()
        assertFailure(raw, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals("Network request timed out (SocketTimeoutException)", raw.errorMessage)
        val logged = apiErrors().single()
        assertEquals(ApiErrorType.NETWORK_TIMEOUT, logged.errorType)
        assertTrue(logged.retryable)
    }

    @Test
    fun networkUnavailable_remainsRetryable() = runBlocking {
        marketRepository.failure = KisMarketException(KisMarketErrorKind.HTTP, "연결 실패")
        val outcome = sync()
        assertFailure(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertCanonical(outcome, AppErrorCode.NETWORK_UNAVAILABLE, ErrorCategory.TRANSIENT)

        marketRepository.failure = IOException("unreachable host")
        val raw = sync()
        assertFailure(raw, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals("Network unavailable (IOException)", raw.errorMessage)
    }

    @Test
    fun http5xx_remainsRetryable() = runBlocking {
        marketRepository.failure = KisMarketException(
            kind = KisMarketErrorKind.HTTP,
            publicMessage = "연결 실패",
            audit = KisMarketErrorAudit(httpCode = 503),
        )
        val outcome = sync()
        assertFailure(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals("연결 실패", outcome.errorMessage)
        assertTrue("repository records provider failures", apiErrors().isEmpty())
    }

    @Test
    fun rateLimit_behaviorUnchanged() = runBlocking {
        marketRepository.failure = KisMarketException(
            kind = KisMarketErrorKind.RATE_LIMITED,
            publicMessage = "KIS 요청 한도 초과",
            audit = KisMarketErrorAudit(msgCd = "EGW00201"),
        )
        val outcome = sync()
        assertEquals(KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS, marketRepository.calls)
        assertFailure(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals("KIS 요청 한도 초과", outcome.errorMessage)
        assertTrue("repository records provider failures", apiErrors().isEmpty())
    }

    @Test
    fun credentialRejectionAndMissing_remainNonRetryable() = runBlocking {
        marketRepository.failure = KisMarketException(
            kind = KisMarketErrorKind.AUTHENTICATION,
            publicMessage = "인증 필요",
            audit = KisMarketErrorAudit(httpCode = 401),
        )
        val outcome = sync()
        assertFailure(outcome, ForwardErrorCode.AUTH_REQUIRED.name, retryable = false)
        assertCanonical(outcome, AppErrorCode.AUTH_REQUIRED, ErrorCategory.SECURITY)

        assertFalse(gateway.ensureCredentials())
        secrets.saveCredentials(KisEnvironment.PRODUCTION, "TEST_APP_KEY", "TEST_APP_SECRET")
        assertTrue(gateway.ensureCredentials())
    }

    @Test
    fun tokenNetworkFailure_isRetryableNotAuthRequired() = runBlocking {
        listOf(KisAuthErrorKind.NETWORK_TIMEOUT, KisAuthErrorKind.NETWORK_UNAVAILABLE).forEach { authKind ->
            marketRepository.failure = KisMarketException(
                kind = KisMarketErrorKind.AUTHENTICATION,
                publicMessage = "인증 토큰 발급 실패",
                authKind = authKind,
            )
            val outcome = sync()
            assertFailure(outcome, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
            assertTrue("KisAuthRepository records the OAuth attempt", apiErrors().isEmpty())
        }
    }

    @Test
    fun tokenRejection_isCredentialRejected() = runBlocking {
        marketRepository.failure = KisMarketException(
            kind = KisMarketErrorKind.AUTHENTICATION,
            publicMessage = "인증 필요",
            audit = KisMarketErrorAudit(httpCode = 403),
            authKind = KisAuthErrorKind.CREDENTIAL_REJECTED,
        )
        val outcome = sync()
        assertFailure(outcome, AppErrorCode.CREDENTIAL_REJECTED.name, retryable = false)
        assertCanonical(outcome, AppErrorCode.CREDENTIAL_REJECTED, ErrorCategory.SECURITY)
        assertTrue(apiErrors().isEmpty())
    }

    @Test
    fun repositoryUnexpected_isUnexpectedWithoutSecondRow() = runBlocking {
        marketRepository.failure = KisMarketException(KisMarketErrorKind.UNEXPECTED, "예상치 못한 오류")
        val outcome = sync()
        assertFailure(outcome, AppErrorCode.UNEXPECTED_EXCEPTION.name, retryable = false)
        assertCanonical(outcome, AppErrorCode.UNEXPECTED_EXCEPTION, ErrorCategory.UNEXPECTED)
        assertEquals("예상치 못한 오류", outcome.errorMessage)
        assertTrue("repository records provider failures", apiErrors().isEmpty())
    }

    @Test
    fun secretBearingExceptionMessage_isNeverExposedOrPersisted() = runBlocking {
        val raw = "session cookie=abc123 acct 12345678-01 {\"body\":\"raw\"} key PSxxxxFAKExxxx"
        marketRepository.failure = IllegalStateException(raw)
        val outcome = sync()
        assertFailure(outcome, AppErrorCode.UNEXPECTED_EXCEPTION.name, retryable = false)
        val logged = apiErrors().single()
        listOf(outcome.errorMessage.orEmpty(), logged.safeMessage).forEach { text ->
            listOf("cookie", "abc123", "12345678", "body", "raw", "PSxxxx").forEach { fragment ->
                assertFalse("leaked '$fragment' in '$text'", text.contains(fragment))
            }
        }
        assertEquals("Unexpected error (IllegalStateException)", outcome.errorMessage)
    }

    @Test
    fun cancellation_isRethrownNotClassified() = runBlocking {
        marketRepository.failure = CancellationException("stopped")
        val thrown = try {
            sync()
            null
        } catch (expected: CancellationException) {
            expected
        }
        assertNotNull(thrown)
        assertTrue(apiErrors().isEmpty())
    }

    @Test
    fun successfulForwardSync_isUnchanged() = runBlocking {
        marketRepository.bars = listOf(bar(THROUGH))
        val first = sync()
        assertTrue(first.success)
        assertEquals(null, first.errorCode)
        assertEquals(1, first.insertedCount)
        assertEquals(THROUGH, localRepository.findLatest(instrumentId)?.tradeDate)

        val second = sync()
        assertEquals(MarketSyncOutcome(success = true, requestedStart = THROUGH), second)
        assertTrue(apiErrors().isEmpty())
    }

    @Test
    fun prepareHistory_classifiesLocalAndUnexpectedFailures_keepsKisBranch() = runBlocking {
        marketRepository.failure = HistoricalSyncException(
            HistoricalSyncErrorKind.INVALID_DATE_RANGE,
            "startDate must be on or before endDate",
        )
        val invariant = gateway.prepareHistory(listOf(instrumentId), THROUGH)
        assertFailure(invariant, AppErrorCode.INTERNAL_INVARIANT_VIOLATION.name, retryable = false)

        marketRepository.failure = IllegalStateException("acct 12345678-01")
        val unexpected = gateway.prepareHistory(listOf(instrumentId), THROUGH)
        assertFailure(unexpected, AppErrorCode.UNEXPECTED_EXCEPTION.name, retryable = false)
        assertEquals("Unexpected error (IllegalStateException)", unexpected.errorMessage)

        marketRepository.failure = KisMarketException(KisMarketErrorKind.HTTP, "연결 실패")
        val kis = gateway.prepareHistory(listOf(instrumentId), THROUGH)
        assertFailure(kis, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true)
        assertEquals("연결 실패", kis.errorMessage)
    }

    private suspend fun sync(): MarketSyncOutcome {
        marketRepository.calls = 0
        database.apiErrorLogDao().deleteOlderThan(NOW.plusSeconds(1))
        return gateway.syncUniverseTo(listOf(instrumentId), THROUGH)
    }

    private suspend fun apiErrors(): List<ApiErrorLogEntity> =
        database.apiErrorLogDao().findSince(Instant.EPOCH)

    private suspend fun insertBar(date: LocalDate) {
        val instrument = localRepository.findInstrumentById(instrumentId)!!
        localRepository.persistDailyBars(instrument, listOf(bar(date)), KisPriceAdjustment.ADJUSTED)
    }

    private fun assertFailure(outcome: MarketSyncOutcome, code: String, retryable: Boolean) {
        assertFalse(outcome.success)
        assertEquals(code, outcome.errorCode)
        assertEquals(retryable, outcome.retryable)
    }

    private fun assertCanonical(
        outcome: MarketSyncOutcome,
        expected: AppErrorCode,
        category: ErrorCategory,
    ) {
        val canonical = AppErrorMapper.fromForwardErrorCodeName(outcome.errorCode)
        assertEquals(expected, canonical)
        assertEquals(category, canonical.category)
        assertEquals(canonical.isRetryableAutomatically, outcome.retryable)
    }

    private fun bar(date: LocalDate) = DailyStockBar(
        symbol = "005930",
        tradeDate = date,
        openPrice = 70_000,
        highPrice = 70_000,
        lowPrice = 70_000,
        closePrice = 70_000,
        volume = 1,
        tradingValue = 70_000,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-29T06:00:00Z")
        val THROUGH: LocalDate = LocalDate.of(2026, 9, 29)
    }
}

private class FailingKisMarketRepository : KisMarketRepository {
    var failure: Throwable? = null
    var bars: List<DailyStockBar> = emptyList()
    var calls = 0

    override suspend fun inquireCurrentPrice(symbol: String): CurrentStockQuote {
        error("current quote is not used")
    }

    override suspend fun inquireDailyBars(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        adjustment: KisPriceAdjustment,
    ): List<DailyStockBar> {
        calls += 1
        failure?.let { throw it }
        return bars.filter { !it.tradeDate.isBefore(startDate) && !it.tradeDate.isAfter(endDate) }
    }
}
