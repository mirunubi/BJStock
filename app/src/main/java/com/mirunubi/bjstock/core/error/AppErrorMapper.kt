package com.mirunubi.bjstock.core.error

import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.kis.KisAuthException
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncErrorKind
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

/**
 * Maps existing typed BJStock errors to [AppErrorCode]
 * (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §4.3). Never inspects message text.
 */
object AppErrorMapper {
    fun fromForwardErrorCode(code: ForwardErrorCode): AppErrorCode = when (code) {
        ForwardErrorCode.AUTH_REQUIRED -> AppErrorCode.AUTH_REQUIRED
        ForwardErrorCode.MISSING_TRADING_POLICY -> AppErrorCode.MISSING_TRADING_POLICY
        ForwardErrorCode.INSUFFICIENT_WARMUP_DATA -> AppErrorCode.INSUFFICIENT_WARMUP_DATA
        ForwardErrorCode.EMPTY_UNIVERSE -> AppErrorCode.EMPTY_UNIVERSE
        ForwardErrorCode.INVALID_RUN -> AppErrorCode.INVALID_RUN_STATE
        ForwardErrorCode.DATA_INTEGRITY_ERROR -> AppErrorCode.DATA_INTEGRITY_ERROR
        ForwardErrorCode.SNAPSHOT_MISSING_PRICE -> AppErrorCode.SNAPSHOT_MISSING_PRICE
        ForwardErrorCode.NETWORK_FAILURE -> AppErrorCode.NETWORK_UNAVAILABLE
        ForwardErrorCode.CYCLE_FAILED -> AppErrorCode.CYCLE_FAILED
    }

    /** Maps a persisted forward error code string; unknown values become UNEXPECTED_EXCEPTION. */
    fun fromForwardErrorCodeName(name: String?): AppErrorCode =
        ForwardErrorCode.entries.firstOrNull { it.name == name }
            ?.let(::fromForwardErrorCode)
            ?: AppErrorCode.fromCode(name)

    fun fromKisMarketErrorKind(kind: KisMarketErrorKind): AppErrorCode = when (kind) {
        KisMarketErrorKind.AUTHENTICATION -> AppErrorCode.AUTH_REQUIRED
        KisMarketErrorKind.HTTP -> AppErrorCode.KIS_SERVER_ERROR
        KisMarketErrorKind.BUSINESS -> AppErrorCode.KIS_BUSINESS_ERROR
        KisMarketErrorKind.INVALID_SYMBOL,
        KisMarketErrorKind.INVALID_DATE_RANGE,
        -> AppErrorCode.INTERNAL_INVARIANT_VIOLATION
        KisMarketErrorKind.RATE_LIMITED -> AppErrorCode.KIS_RATE_LIMIT
        KisMarketErrorKind.NETWORK_TIMEOUT -> AppErrorCode.NETWORK_TIMEOUT
        KisMarketErrorKind.MALFORMED_RESPONSE,
        KisMarketErrorKind.MAPPING_FAILURE,
        -> AppErrorCode.KIS_MALFORMED_RESPONSE
    }

    /** [KisAuthException] carries no typed kind yet; only the HTTP code is trusted. */
    fun fromKisAuthHttpCode(httpCode: Int?): AppErrorCode = when {
        httpCode == 401 || httpCode == 403 -> AppErrorCode.CREDENTIAL_REJECTED
        httpCode != null && httpCode in 500..599 -> AppErrorCode.KIS_SERVER_ERROR
        else -> AppErrorCode.AUTH_REQUIRED
    }

    fun fromHistoricalSyncErrorKind(kind: HistoricalSyncErrorKind): AppErrorCode = when (kind) {
        HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND -> AppErrorCode.DATA_INTEGRITY_ERROR
        HistoricalSyncErrorKind.INVALID_DATE_RANGE,
        HistoricalSyncErrorKind.NO_LATEST_BAR,
        -> AppErrorCode.INTERNAL_INVARIANT_VIOLATION
    }

    fun fromThrowable(
        throwable: Throwable,
        logicalEndpoint: String? = null,
        attempt: Int? = null,
    ): SafeAppError {
        if (throwable is CancellationException) throw throwable
        val exceptionType = SafeLogText.exceptionType(throwable.javaClass.simpleName)
        return when (throwable) {
            is KisMarketException -> SafeAppError(
                code = fromKisMarketErrorKind(throwable.kind),
                diagnostics = SafeDiagnostics(
                    exceptionType = exceptionType,
                    httpStatus = throwable.audit?.httpCode?.takeIf { it in 100..599 },
                    businessCode = SafeLogText.businessCode(throwable.audit?.msgCd),
                    logicalEndpoint = logicalEndpoint,
                    attempt = attempt,
                ),
            )
            is KisAuthException -> SafeAppError(
                code = fromKisAuthHttpCode(throwable.httpCode),
                diagnostics = SafeDiagnostics(
                    exceptionType = exceptionType,
                    httpStatus = throwable.httpCode?.takeIf { it in 100..599 },
                    logicalEndpoint = logicalEndpoint,
                    attempt = attempt,
                ),
            )
            is HistoricalSyncException -> SafeAppError(
                code = fromHistoricalSyncErrorKind(throwable.kind),
                diagnostics = SafeDiagnostics(
                    exceptionType = exceptionType,
                    logicalEndpoint = logicalEndpoint,
                    attempt = attempt,
                ),
            )
            is SocketTimeoutException -> networkError(
                AppErrorCode.NETWORK_TIMEOUT, exceptionType, logicalEndpoint, attempt,
            )
            is IOException -> networkError(
                AppErrorCode.NETWORK_UNAVAILABLE, exceptionType, logicalEndpoint, attempt,
            )
            else -> SafeAppError(
                code = AppErrorCode.UNEXPECTED_EXCEPTION,
                diagnostics = SafeDiagnostics(
                    exceptionType = exceptionType,
                    logicalEndpoint = logicalEndpoint,
                    attempt = attempt,
                ),
            )
        }
    }

    private fun networkError(
        code: AppErrorCode,
        exceptionType: String?,
        logicalEndpoint: String?,
        attempt: Int?,
    ) = SafeAppError(
        code = code,
        diagnostics = SafeDiagnostics(
            exceptionType = exceptionType,
            logicalEndpoint = logicalEndpoint,
            attempt = attempt,
        ),
    )
}
