package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.database.dao.ApiErrorLogDao
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.kis.KisAuthLogger
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException

/**
 * [evidenceFailureLogger] is the fallback channel for failures to write or clean up this log
 * (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §20.11). It receives only fixed text, a logical
 * name, and an exception class name; it must never be this service itself.
 */
class ApiErrorLogService(
    private val dao: ApiErrorLogDao,
    private val evidenceFailureLogger: KisAuthLogger,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun record(
        provider: ApiErrorProvider,
        operation: String,
        errorType: ApiErrorType,
        safeMessage: String,
        retryable: Boolean = false,
        httpStatus: Int? = null,
        businessCode: String? = null,
        strategyRunId: Long? = null,
        forwardCycleId: Long? = null,
        occurredAt: Instant = now(),
    ): Long = dao.insert(
        ApiErrorLogEntity(
            provider = provider,
            operation = operation,
            errorType = errorType,
            httpStatus = httpStatus,
            businessCode = businessCode,
            safeMessage = sanitize(safeMessage),
            retryable = retryable,
            strategyRunId = strategyRunId,
            forwardCycleId = forwardCycleId,
            occurredAt = occurredAt,
            operationId = currentForwardOperationId(),
        ),
    )

    /**
     * Secondary evidence write for a primary failure the caller is about to throw or return.
     * A failed write never replaces that primary failure: it is reported once to the fallback
     * logger and is never recorded here again. Cancellation is rethrown.
     */
    suspend fun recordOrReport(
        provider: ApiErrorProvider,
        operation: String,
        errorType: ApiErrorType,
        safeMessage: String,
        retryable: Boolean = false,
        httpStatus: Int? = null,
        businessCode: String? = null,
    ): Boolean = try {
        record(
            provider = provider,
            operation = operation,
            errorType = errorType,
            safeMessage = safeMessage,
            retryable = retryable,
            httpStatus = httpStatus,
            businessCode = businessCode,
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        reportFailure(EVIDENCE_WRITE_FAILED, operation, failure)
        false
    }

    /**
     * Rolling 7-day retention: delete rows with occurred_at strictly before (now - 7 days).
     * Keeps the last 7 calendar days inclusive of "today" at cutoff boundary.
     */
    suspend fun cleanupOlderThanSevenDays(reference: Instant = now()): Int {
        val cutoff = reference.minus(7, ChronoUnit.DAYS)
        return dao.deleteOlderThan(cutoff)
    }

    /** Diagnostics cleanup never fails its caller; a failure is reported and null is returned. */
    suspend fun cleanupOrReport(trigger: String): Int? = try {
        cleanupOlderThanSevenDays()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        reportFailure(CLEANUP_FAILED, trigger, failure)
        null
    }

    suspend fun findRecentSevenDays(limit: Int = 100): List<ApiErrorLogEntity> {
        val since = now().minus(7, ChronoUnit.DAYS)
        return dao.findSince(since, limit)
    }

    private fun reportFailure(prefix: String, logicalName: String, failure: Exception) {
        val name = logicalName.takeIf(SafeLogText::isCode) ?: UNKNOWN_NAME
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        evidenceFailureLogger.info("$prefix: $name ($type)")
    }

    companion object {
        const val EVIDENCE_WRITE_FAILED = "API error evidence write failed"
        const val CLEANUP_FAILED = "API error cleanup failed"
        const val CLEANUP_APP_START = "APP_START"
        const val CLEANUP_FORWARD_SUCCESS = "FORWARD_SUCCESS"
        private const val UNKNOWN_NAME = "UNKNOWN"

        fun sanitize(message: String): String {
            val lowered = message.lowercase()
            val secrets = listOf(
                "appkey", "appsecret", "app_key", "app_secret",
                "authorization", "bearer", "access_token", "access token",
                "account", "acctno",
            )
            if (secrets.any { lowered.contains(it) }) {
                return "secure error details omitted"
            }
            return message.take(500)
        }
    }
}
