package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.model.ApiErrorType

object KisApiErrorMapper {
    fun fromMarketKind(kind: KisMarketErrorKind): ApiErrorType = when (kind) {
        KisMarketErrorKind.NETWORK_TIMEOUT -> ApiErrorType.NETWORK_TIMEOUT
        KisMarketErrorKind.HTTP -> ApiErrorType.HTTP_ERROR
        KisMarketErrorKind.AUTHENTICATION -> ApiErrorType.AUTH_ERROR
        KisMarketErrorKind.BUSINESS -> ApiErrorType.KIS_BUSINESS_ERROR
        KisMarketErrorKind.RATE_LIMITED -> ApiErrorType.RATE_LIMIT
        KisMarketErrorKind.MALFORMED_RESPONSE,
        KisMarketErrorKind.MAPPING_FAILURE,
        -> ApiErrorType.MALFORMED_RESPONSE
        KisMarketErrorKind.INVALID_SYMBOL,
        KisMarketErrorKind.INVALID_DATE_RANGE,
        -> ApiErrorType.LOCAL_INVARIANT
    }

    fun isRetryable(kind: KisMarketErrorKind): Boolean = when (kind) {
        KisMarketErrorKind.NETWORK_TIMEOUT,
        KisMarketErrorKind.HTTP,
        KisMarketErrorKind.RATE_LIMITED,
        -> true
        KisMarketErrorKind.AUTHENTICATION,
        KisMarketErrorKind.BUSINESS,
        KisMarketErrorKind.MALFORMED_RESPONSE,
        KisMarketErrorKind.INVALID_SYMBOL,
        KisMarketErrorKind.INVALID_DATE_RANGE,
        KisMarketErrorKind.MAPPING_FAILURE,
        -> false
    }

    /**
     * API error type for a canonical code (docs/148 taxonomy).
     * Domain codes are not API errors and return null.
     */
    fun fromAppErrorCode(code: AppErrorCode): ApiErrorType? = when (code) {
        AppErrorCode.NETWORK_UNAVAILABLE,
        AppErrorCode.NETWORK_TIMEOUT,
        -> ApiErrorType.NETWORK_TIMEOUT
        AppErrorCode.KIS_SERVER_ERROR -> ApiErrorType.HTTP_ERROR
        AppErrorCode.KIS_RATE_LIMIT -> ApiErrorType.RATE_LIMIT
        AppErrorCode.CREDENTIAL_MISSING,
        AppErrorCode.CREDENTIAL_REJECTED,
        AppErrorCode.AUTH_REQUIRED,
        -> ApiErrorType.AUTH_ERROR
        AppErrorCode.KIS_BUSINESS_ERROR -> ApiErrorType.KIS_BUSINESS_ERROR
        AppErrorCode.KIS_MALFORMED_RESPONSE -> ApiErrorType.MALFORMED_RESPONSE
        AppErrorCode.DATA_INTEGRITY_ERROR,
        AppErrorCode.LEDGER_MISMATCH,
        AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT,
        AppErrorCode.INTERNAL_INVARIANT_VIOLATION,
        -> ApiErrorType.LOCAL_INVARIANT
        AppErrorCode.UNEXPECTED_EXCEPTION -> ApiErrorType.UNEXPECTED
        AppErrorCode.NO_POSITION_TO_SELL,
        AppErrorCode.POSITION_ALREADY_OPEN,
        AppErrorCode.INSUFFICIENT_CASH,
        AppErrorCode.INSUFFICIENT_WARMUP_DATA,
        AppErrorCode.INVALID_RUN_STATE,
        AppErrorCode.EMPTY_UNIVERSE,
        AppErrorCode.MISSING_TRADING_POLICY,
        AppErrorCode.SNAPSHOT_MISSING_PRICE,
        AppErrorCode.CYCLE_FAILED,
        -> null
    }
}
