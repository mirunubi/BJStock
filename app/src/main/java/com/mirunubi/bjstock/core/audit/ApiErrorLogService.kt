package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.database.dao.ApiErrorLogDao
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import java.time.Instant
import java.time.temporal.ChronoUnit

class ApiErrorLogService(
    private val dao: ApiErrorLogDao,
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
        ),
    )

    /**
     * Rolling 7-day retention: delete rows with occurred_at strictly before (now - 7 days).
     * Keeps the last 7 calendar days inclusive of "today" at cutoff boundary.
     */
    suspend fun cleanupOlderThanSevenDays(reference: Instant = now()): Int {
        val cutoff = reference.minus(7, ChronoUnit.DAYS)
        return dao.deleteOlderThan(cutoff)
    }

    suspend fun findRecentSevenDays(limit: Int = 100): List<ApiErrorLogEntity> {
        val since = now().minus(7, ChronoUnit.DAYS)
        return dao.findSince(since, limit)
    }

    companion object {
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
