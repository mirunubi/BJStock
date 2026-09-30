package com.mirunubi.bjstock.core.audit

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.kis.RecordingKisAuthLogger
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiErrorLogServiceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var service: ApiErrorLogService
    private var now: Instant = Instant.parse("2026-09-23T00:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ApiErrorLogService(database.apiErrorLogDao(), RecordingKisAuthLogger()) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun httpError_isRecordedWithoutSecrets() = runBlocking {
        service.record(
            provider = ApiErrorProvider.KIS,
            operation = "KIS_DAILY_PRICE",
            errorType = ApiErrorType.HTTP_ERROR,
            safeMessage = "연결 실패",
            httpStatus = 500,
            retryable = true,
        )
        val rows = service.findRecentSevenDays()
        assertEquals(1, rows.size)
        assertEquals(500, rows.single().httpStatus)
        assertEquals(ApiErrorType.HTTP_ERROR, rows.single().errorType)
        assertFalse(rows.single().safeMessage.lowercase().contains("appkey"))
        assertFalse(rows.single().safeMessage.lowercase().contains("secret"))
    }

    @Test
    fun authError_sanitizesSecretLikeMessages() = runBlocking {
        val id = service.record(
            provider = ApiErrorProvider.KIS,
            operation = "KIS_OAUTH",
            errorType = ApiErrorType.AUTH_ERROR,
            safeMessage = "failed authorization bearer ACCESS_TOKEN appkey=SECRET",
            httpStatus = 401,
        )
        val row = database.apiErrorLogDao().findSince(now.minus(7, ChronoUnit.DAYS))
            .first { it.id == id }
        assertEquals("secure error details omitted", row.safeMessage)
        assertEquals(ApiErrorType.AUTH_ERROR, row.errorType)
    }

    @Test
    fun sevenDayCleanup_deletesOlderThanCutoff() = runBlocking {
        suspend fun insertAt(daysAgo: Long) {
            service.record(
                provider = ApiErrorProvider.KIS,
                operation = "KIS_CURRENT_PRICE",
                errorType = ApiErrorType.NETWORK_TIMEOUT,
                safeMessage = "old-$daysAgo",
                occurredAt = now.minus(daysAgo, ChronoUnit.DAYS),
            )
        }
        insertAt(8)
        insertAt(7)
        insertAt(3)
        insertAt(0)
        val deleted = service.cleanupOlderThanSevenDays(now)
        assertEquals(1, deleted)
        val remaining = service.findRecentSevenDays()
        assertEquals(3, remaining.size)
        assertTrue(remaining.none { it.safeMessage == "old-8" })
    }

    @Test
    fun cleanup_doesNotTouchTradeAuditLogs() = runBlocking {
        val strategyId = database.strategyDao().insertStrategy(
            StrategyEntity(
                strategyCode = "A",
                strategyName = "A",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val versionId = database.strategyDao().insertVersion(
            StrategyVersionEntity(
                strategyId = strategyId,
                versionNo = 1,
                buyThreshold = 700_000,
                sellThreshold = 400_000,
                status = StrategyVersionStatus.ACTIVE,
                createdAt = Instant.EPOCH,
            ),
        )
        val runId = database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = "r",
                strategyVersionId = versionId,
                runType = RunType.PAPER,
                startDate = LocalDate.of(2026, 9, 1),
                endDate = null,
                initialCash = 1L,
                status = RunStatus.READY,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        database.tradeAuditLogDao().insert(
            TradeAuditLogEntity(
                strategyRunId = runId,
                eventType = TradeAuditEventType.EVALUATION_DECIDED,
                eventKey = "evaluation:1:decision",
                reasonText = "keep me",
                createdAt = Instant.EPOCH,
            ),
        )
        service.record(
            provider = ApiErrorProvider.KIS,
            operation = "KIS_OAUTH",
            errorType = ApiErrorType.AUTH_ERROR,
            safeMessage = "x",
            occurredAt = now.minus(10, ChronoUnit.DAYS),
        )
        val beforeTrade = database.tradeAuditLogDao().countAll()
        service.cleanupOlderThanSevenDays(now)
        assertEquals(beforeTrade, database.tradeAuditLogDao().countAll())
        assertEquals(0, database.apiErrorLogDao().countAll())
    }

    @Test
    fun recordOrReport_success_writesExactlyOneRowAndNoFallback() = runBlocking {
        val fallback = RecordingKisAuthLogger()
        val written = ApiErrorLogService(database.apiErrorLogDao(), fallback) { now }.recordOrReport(
            provider = ApiErrorProvider.KIS,
            operation = "KIS_OAUTH",
            errorType = ApiErrorType.NETWORK_TIMEOUT,
            safeMessage = "KIS token request failed: network timeout",
            retryable = true,
        )
        assertTrue(written)
        assertEquals(1, database.apiErrorLogDao().countAll())
        assertTrue(fallback.messages.isEmpty())
    }

    @Test
    fun recordOrReport_writeFailure_reportsClassOnlyWithoutRecursion() = runBlocking {
        val dao = InMemoryApiErrorLogDao().apply { insertFailure = SQLiteException(SECRET_BEARING_SQL) }
        val fallback = RecordingKisAuthLogger()
        val written = ApiErrorLogService(dao, fallback) { now }.recordOrReport(
            provider = ApiErrorProvider.KIS,
            operation = "KIS_DAILY_PRICE",
            errorType = ApiErrorType.HTTP_ERROR,
            safeMessage = "연결 실패",
            retryable = true,
        )
        assertFalse(written)
        assertEquals(1, dao.insertAttempts)
        assertTrue(dao.rows.isEmpty())
        assertEquals(
            listOf("API error evidence write failed: KIS_DAILY_PRICE (SQLiteException)"),
            fallback.messages,
        )
        assertNoRawText(fallback.messages)
    }

    @Test
    fun recordOrReport_unsafeOperationName_isNotEchoed() = runBlocking {
        val dao = InMemoryApiErrorLogDao().apply { insertFailure = SQLiteException(SECRET_BEARING_SQL) }
        val fallback = RecordingKisAuthLogger()
        ApiErrorLogService(dao, fallback) { now }.recordOrReport(
            provider = ApiErrorProvider.KIS,
            operation = "appsecret TEST_APP_SECRET",
            errorType = ApiErrorType.UNEXPECTED,
            safeMessage = "x",
        )
        assertEquals(listOf("API error evidence write failed: UNKNOWN (SQLiteException)"), fallback.messages)
    }

    @Test
    fun recordOrReport_cancellation_isRethrownWithoutFallback() {
        val dao = InMemoryApiErrorLogDao().apply { insertFailure = CancellationException("stopped") }
        val fallback = RecordingKisAuthLogger()
        val thrown = runCatching {
            runBlocking {
                ApiErrorLogService(dao, fallback) { now }.recordOrReport(
                    provider = ApiErrorProvider.KIS,
                    operation = "KIS_OAUTH",
                    errorType = ApiErrorType.AUTH_ERROR,
                    safeMessage = "x",
                )
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(fallback.messages.isEmpty())
    }

    @Test
    fun cleanupOrReport_success_deletesLikeSevenDayCleanup() = runBlocking {
        val fallback = RecordingKisAuthLogger()
        val reporting = ApiErrorLogService(database.apiErrorLogDao(), fallback) { now }
        listOf(8L, 3L).forEach { daysAgo ->
            reporting.record(
                provider = ApiErrorProvider.KIS,
                operation = "KIS_CURRENT_PRICE",
                errorType = ApiErrorType.NETWORK_TIMEOUT,
                safeMessage = "old-$daysAgo",
                occurredAt = now.minus(daysAgo, ChronoUnit.DAYS),
            )
        }
        assertEquals(1, reporting.cleanupOrReport(ApiErrorLogService.CLEANUP_APP_START))
        assertEquals(1, database.apiErrorLogDao().countAll())
        assertTrue(fallback.messages.isEmpty())
    }

    @Test
    fun cleanupOrReport_failure_isContainedAndReportedSafely() = runBlocking {
        val dao = InMemoryApiErrorLogDao().apply { cleanupFailure = SQLiteException(SECRET_BEARING_SQL) }
        val fallback = RecordingKisAuthLogger()
        val result = ApiErrorLogService(dao, fallback) { now }
            .cleanupOrReport(ApiErrorLogService.CLEANUP_FORWARD_SUCCESS)
        assertEquals(null, result)
        assertEquals(listOf("API error cleanup failed: FORWARD_SUCCESS (SQLiteException)"), fallback.messages)
        assertEquals(0, dao.insertAttempts)
        assertNoRawText(fallback.messages)
    }

    @Test
    fun cleanupOrReport_cancellation_isRethrown() {
        val dao = InMemoryApiErrorLogDao().apply { cleanupFailure = CancellationException("stopped") }
        val fallback = RecordingKisAuthLogger()
        val thrown = runCatching {
            runBlocking { ApiErrorLogService(dao, fallback) { now }.cleanupOrReport("APP_START") }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(fallback.messages.isEmpty())
    }

    private fun assertNoRawText(messages: List<String>) {
        messages.forEach { message ->
            listOf("INSERT", "api_error_logs", "appsecret", "TEST_APP_SECRET").forEach { fragment ->
                assertFalse("leaked '$fragment' in '$message'", message.contains(fragment))
            }
        }
    }

    @Test
    fun mapper_mapsMarketKinds() {
        assertEquals(ApiErrorType.AUTH_ERROR, KisApiErrorMapper.fromMarketKind(KisMarketErrorKind.AUTHENTICATION))
        assertEquals(ApiErrorType.HTTP_ERROR, KisApiErrorMapper.fromMarketKind(KisMarketErrorKind.HTTP))
        assertEquals(
            ApiErrorType.NETWORK_TIMEOUT,
            KisApiErrorMapper.fromMarketKind(KisMarketErrorKind.NETWORK_TIMEOUT),
        )
        assertEquals(
            ApiErrorType.KIS_BUSINESS_ERROR,
            KisApiErrorMapper.fromMarketKind(KisMarketErrorKind.BUSINESS),
        )
        assertEquals(
            ApiErrorType.MALFORMED_RESPONSE,
            KisApiErrorMapper.fromMarketKind(KisMarketErrorKind.MALFORMED_RESPONSE),
        )
    }

    private companion object {
        const val SECRET_BEARING_SQL = "INSERT INTO api_error_logs failed near appsecret TEST_APP_SECRET"
    }
}
