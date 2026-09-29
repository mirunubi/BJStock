package com.mirunubi.bjstock.core.error

import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.kis.KisAuthException
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorAudit
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncErrorKind
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorModelTest {
    @Test
    fun catalog_containsRequiredSystemCodes() {
        assertEquals(ErrorCategory.UNEXPECTED, AppErrorCode.UNEXPECTED_EXCEPTION.category)
        assertEquals(ErrorCategory.INVARIANT, AppErrorCode.INTERNAL_INVARIANT_VIOLATION.category)
    }

    @Test
    fun unknownOrNullCode_mapsToUnexpectedException() {
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, AppErrorCode.fromCode(null))
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, AppErrorCode.fromCode("SOMETHING_NEW"))
        assertEquals(AppErrorCode.NO_POSITION_TO_SELL, AppErrorCode.fromCode("NO_POSITION_TO_SELL"))
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, AppErrorMapper.fromForwardErrorCodeName("garbage"))
    }

    @Test
    fun catalog_invariantsHold() {
        AppErrorCode.entries.forEach { code ->
            assertTrue("${code.name} safe message must pass allowlist", SafeLogText.isSafeMessage(code.safeMessage))
            if (code.category == ErrorCategory.INVARIANT) {
                assertEquals(code.name, RetryPolicy.NONE, code.retryPolicy)
                assertEquals(code.name, OperationAction.ABORT_OPERATION, code.operationAction)
            }
            if (code.category == ErrorCategory.SECURITY) {
                assertEquals(code.name, RetryPolicy.USER_ACTION_REQUIRED, code.retryPolicy)
            }
            if (code.retryPolicy == RetryPolicy.USER_ACTION_REQUIRED) {
                assertTrue(code.name, code.userActionRequired)
                assertFalse(code.name, code.isRetryableAutomatically)
            }
        }
    }

    @Test
    fun representativeErrors_mapCategorySeverityRetryAndAction() {
        assertClassification(AppErrorCode.KIS_RATE_LIMIT, ErrorCategory.TRANSIENT, ErrorSeverity.DEGRADED, RetryPolicy.FIXED_DELAY, OperationAction.RETRY_OPERATION)
        assertClassification(AppErrorCode.NETWORK_TIMEOUT, ErrorCategory.TRANSIENT, ErrorSeverity.DEGRADED, RetryPolicy.EXPONENTIAL_BACKOFF, OperationAction.RETRY_OPERATION)
        assertClassification(AppErrorCode.CREDENTIAL_REJECTED, ErrorCategory.SECURITY, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, OperationAction.REQUIRE_USER_ACTION)
        assertClassification(AppErrorCode.CYCLE_FAILED, ErrorCategory.DOMAIN, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, OperationAction.REQUIRE_USER_ACTION)
        assertClassification(AppErrorCode.INTERNAL_INVARIANT_VIOLATION, ErrorCategory.INVARIANT, ErrorSeverity.CRITICAL, RetryPolicy.NONE, OperationAction.ABORT_OPERATION)
        assertClassification(AppErrorCode.NO_POSITION_TO_SELL, ErrorCategory.DOMAIN, ErrorSeverity.INFO, RetryPolicy.NONE, OperationAction.SKIP)
        assertTrue(AppErrorCode.NO_POSITION_TO_SELL.auditRequired)
        assertTrue(AppErrorCode.INSUFFICIENT_CASH.auditRequired)
        assertTrue(ErrorSeverity.FINANCIAL_INTEGRITY > ErrorSeverity.CRITICAL)
        assertEquals(ErrorSeverity.entries.last(), ErrorSeverity.FINANCIAL_INTEGRITY)
    }

    @Test
    fun financialIntegrityCodes_areClassifiedAndPreservedByTheMapper() {
        listOf(AppErrorCode.LEDGER_MISMATCH, AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT).forEach { code ->
            assertClassification(code, ErrorCategory.INVARIANT, ErrorSeverity.FINANCIAL_INTEGRITY, RetryPolicy.NONE, OperationAction.ABORT_OPERATION)
            assertTrue(code.name, code.userActionRequired)
            assertTrue(code.name, code.auditRequired)
        }
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, AppErrorCode.fromCode("DUPLICATE_EXECUTION"))

        val ledger = SafeAppError.fromThrowable(IntegrityViolationException.ledgerMismatch("LEDGER_BALANCE_MISMATCH"))
        assertEquals(AppErrorCode.LEDGER_MISMATCH, ledger.code)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, ledger.severity)
        assertEquals("IntegrityViolationException", ledger.diagnostics.exceptionType)

        val execution = SafeAppError.fromThrowable(IntegrityViolationException.executionConflict("EXECUTION_REPLAY_MISMATCH"))
        assertEquals(AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT, execution.code)
        assertEquals(ErrorSeverity.FINANCIAL_INTEGRITY, execution.severity)

        val invariant = IntegrityViolationException.invariant("AUDIT_EVENT_KEY_CONFLICT")
        assertEquals(ErrorSeverity.CRITICAL, SafeAppError.fromThrowable(invariant).severity)
        assertEquals("AUDIT_EVENT_KEY_CONFLICT", invariant.message)
    }

    @Test
    fun existingForwardAndKisCodes_mapToCanonicalCodes() {
        ForwardErrorCode.entries.forEach { assertNotNull(AppErrorMapper.fromForwardErrorCode(it)) }
        assertEquals(AppErrorCode.INVALID_RUN_STATE, AppErrorMapper.fromForwardErrorCode(ForwardErrorCode.INVALID_RUN))
        assertEquals(AppErrorCode.NETWORK_UNAVAILABLE, AppErrorMapper.fromForwardErrorCode(ForwardErrorCode.NETWORK_FAILURE))
        assertEquals(AppErrorCode.AUTH_REQUIRED, AppErrorMapper.fromForwardErrorCodeName("AUTH_REQUIRED"))
        assertEquals(AppErrorCode.KIS_SERVER_ERROR, AppErrorMapper.fromKisMarketErrorKind(KisMarketErrorKind.HTTP))
        assertEquals(AppErrorCode.KIS_RATE_LIMIT, AppErrorMapper.fromKisMarketErrorKind(KisMarketErrorKind.RATE_LIMITED))
        assertEquals(AppErrorCode.KIS_MALFORMED_RESPONSE, AppErrorMapper.fromKisMarketErrorKind(KisMarketErrorKind.MAPPING_FAILURE))
        assertEquals(AppErrorCode.CREDENTIAL_REJECTED, AppErrorMapper.fromKisAuthHttpCode(401))
        assertEquals(AppErrorCode.KIS_SERVER_ERROR, AppErrorMapper.fromKisAuthHttpCode(503))
        assertEquals(AppErrorCode.AUTH_REQUIRED, AppErrorMapper.fromKisAuthHttpCode(null))
    }

    @Test
    fun fromThrowable_unexpectedException_keepsOnlySafeDiagnostics() {
        val error = SafeAppError.fromThrowable(
            IllegalStateException("appsecret=REAL-SECRET Authorization: Bearer tokenvalue"),
            logicalEndpoint = "KIS_DAILY_PRICE",
            attempt = 2,
        )

        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION, error.code)
        assertEquals(ErrorCategory.UNEXPECTED, error.category)
        assertEquals(RetryPolicy.NONE, error.retryPolicy)
        assertEquals(OperationAction.ABORT_OPERATION, error.operationAction)
        assertEquals("Unexpected error", error.safeMessage)
        assertEquals("IllegalStateException", error.diagnostics.exceptionType)
        assertEquals("KIS_DAILY_PRICE", error.diagnostics.logicalEndpoint)
        assertEquals(2, error.diagnostics.attempt)
        val rendered = error.toString().lowercase()
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("bearer"))
        assertFalse(rendered.contains("tokenvalue"))
    }

    @Test
    fun fromThrowable_knownExceptions() {
        val rateLimited = SafeAppError.fromThrowable(
            KisMarketException(
                kind = KisMarketErrorKind.RATE_LIMITED,
                publicMessage = "rate limited",
                audit = KisMarketErrorAudit(msgCd = "EGW00201", httpCode = 500, msg1 = "free text from KIS"),
            ),
        )
        assertEquals(AppErrorCode.KIS_RATE_LIMIT, rateLimited.code)
        assertEquals(500, rateLimited.diagnostics.httpStatus)
        assertEquals("EGW00201", rateLimited.diagnostics.businessCode)
        assertFalse(rateLimited.toString().contains("free text"))

        assertEquals(AppErrorCode.CREDENTIAL_REJECTED, SafeAppError.fromThrowable(KisAuthException("x", 401)).code)
        assertEquals(AppErrorCode.NETWORK_TIMEOUT, SafeAppError.fromThrowable(SocketTimeoutException("t")).code)
        assertEquals(AppErrorCode.NETWORK_UNAVAILABLE, SafeAppError.fromThrowable(IOException("io")).code)
        assertEquals(
            AppErrorCode.INTERNAL_INVARIANT_VIOLATION,
            SafeAppError.fromThrowable(HistoricalSyncException(HistoricalSyncErrorKind.NO_LATEST_BAR, "x")).code,
        )
        assertEquals(
            AppErrorCode.DATA_INTEGRITY_ERROR,
            SafeAppError.fromThrowable(HistoricalSyncException(HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND, "x")).code,
        )
    }

    @Test
    fun fromThrowable_rethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            SafeAppError.fromThrowable(CancellationException("cancelled"))
        }
    }

    @Test
    fun safeAppError_unsafeOverrideFallsBackToCatalogMessage() {
        val error = SafeAppError(AppErrorCode.NETWORK_TIMEOUT, messageOverride = "token=abc")
        assertEquals(AppErrorCode.NETWORK_TIMEOUT.safeMessage, error.safeMessage)
        val contextual = SafeAppError(AppErrorCode.NETWORK_TIMEOUT, messageOverride = "Run 3 sync timed out")
        assertEquals("Run 3 sync timed out", contextual.safeMessage)
    }

    @Test
    fun safeDiagnostics_rejectsNonAllowlistedShapes() {
        assertThrows(IllegalArgumentException::class.java) { SafeDiagnostics(exceptionType = "java.lang.Foo bar") }
        assertThrows(IllegalArgumentException::class.java) { SafeDiagnostics(businessCode = "Bearer abc") }
        assertThrows(IllegalArgumentException::class.java) { SafeDiagnostics(logicalEndpoint = "/uapi/x") }
        assertThrows(IllegalArgumentException::class.java) { SafeDiagnostics(httpStatus = 42) }
    }

    @Test
    fun documentCatalogTable_matchesAppErrorCode() {
        val rows = resolveDoc().readLines()
            .map { it.trim() }
            .filter { it.startsWith("| `") }
            .mapNotNull { line ->
                val cells = line.trim('|').split('|').map { it.trim().trim('`') }
                if (cells.size == 7) cells else null
            }
            .associateBy { it[0] }
        AppErrorCode.entries.forEach { code ->
            val row = rows[code.name]
            assertNotNull("docs/150 §4.1 is missing ${code.name}", row)
            row!!
            assertEquals(code.name, code.category.name, row[1])
            assertEquals(code.name, code.severity.name, row[2])
            assertEquals(code.name, code.retryPolicy.name, row[3])
            assertEquals(code.name, if (code.userActionRequired) "yes" else "no", row[4])
            assertEquals(code.name, code.operationAction.name, row[5])
            assertEquals(code.name, if (code.auditRequired) "yes" else "no", row[6])
        }
    }

    private fun assertClassification(
        code: AppErrorCode,
        category: ErrorCategory,
        severity: ErrorSeverity,
        retry: RetryPolicy,
        action: OperationAction,
    ) {
        assertEquals(code.name, category, code.category)
        assertEquals(code.name, severity, code.severity)
        assertEquals(code.name, retry, code.retryPolicy)
        assertEquals(code.name, action, code.operationAction)
    }

    private fun resolveDoc(): File {
        val relative = "docs/150_OPERATIONAL_RELIABILITY_STANDARD.md"
        val found = listOf(File(relative), File("../$relative"), File("../../$relative")).firstOrNull { it.isFile }
        assertNotNull("standard not found from ${System.getProperty("user.dir")}", found)
        return found!!
    }
}
