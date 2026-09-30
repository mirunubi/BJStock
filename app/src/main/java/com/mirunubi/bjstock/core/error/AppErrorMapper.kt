package com.mirunubi.bjstock.core.error

import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.kis.KisAuthErrorKind
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
        KisMarketErrorKind.UNEXPECTED -> AppErrorCode.UNEXPECTED_EXCEPTION
    }

    /** A token failure wrapped by the market repository keeps its typed auth cause. */
    fun fromKisMarketException(error: KisMarketException): AppErrorCode =
        error.authKind?.let(::fromKisAuthErrorKind) ?: fromKisMarketErrorKind(error.kind)

    fun fromKisAuthErrorKind(kind: KisAuthErrorKind): AppErrorCode = when (kind) {
        KisAuthErrorKind.CREDENTIAL_MISSING -> AppErrorCode.CREDENTIAL_MISSING
        KisAuthErrorKind.CREDENTIAL_REJECTED -> AppErrorCode.CREDENTIAL_REJECTED
        KisAuthErrorKind.AUTH_REQUIRED -> AppErrorCode.AUTH_REQUIRED
        KisAuthErrorKind.SERVER_ERROR -> AppErrorCode.KIS_SERVER_ERROR
        KisAuthErrorKind.NETWORK_TIMEOUT -> AppErrorCode.NETWORK_TIMEOUT
        KisAuthErrorKind.NETWORK_UNAVAILABLE -> AppErrorCode.NETWORK_UNAVAILABLE
        KisAuthErrorKind.MALFORMED_RESPONSE -> AppErrorCode.KIS_MALFORMED_RESPONSE
        KisAuthErrorKind.UNEXPECTED -> AppErrorCode.UNEXPECTED_EXCEPTION
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
                code = fromKisMarketException(throwable),
                diagnostics = SafeDiagnostics(
                    exceptionType = exceptionType,
                    httpStatus = throwable.audit?.httpCode?.takeIf { it in 100..599 },
                    businessCode = SafeLogText.businessCode(throwable.audit?.msgCd),
                    logicalEndpoint = logicalEndpoint,
                    attempt = attempt,
                ),
            )
            is KisAuthException -> SafeAppError(
                code = fromKisAuthErrorKind(throwable.kind),
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
            // Keeps the specific code: LEDGER_MISMATCH / EXECUTION_IDEMPOTENCY_CONFLICT /
            // FILLED_ORDER_WITHOUT_EXECUTION carry FINANCIAL_INTEGRITY.
            is IntegrityViolationException -> SafeAppError(
                code = throwable.code,
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
