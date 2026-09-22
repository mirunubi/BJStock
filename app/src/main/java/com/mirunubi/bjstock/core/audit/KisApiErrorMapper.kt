package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.model.ApiErrorType

object KisApiErrorMapper {
    fun fromMarketKind(kind: KisMarketErrorKind): ApiErrorType = when (kind) {
        KisMarketErrorKind.NETWORK_TIMEOUT -> ApiErrorType.NETWORK_TIMEOUT
        KisMarketErrorKind.HTTP -> ApiErrorType.HTTP_ERROR
        KisMarketErrorKind.AUTHENTICATION -> ApiErrorType.AUTH_ERROR
        KisMarketErrorKind.BUSINESS -> ApiErrorType.KIS_BUSINESS_ERROR
        KisMarketErrorKind.MALFORMED_RESPONSE,
        KisMarketErrorKind.MAPPING_FAILURE,
        -> ApiErrorType.MALFORMED_RESPONSE
        KisMarketErrorKind.INVALID_SYMBOL,
        KisMarketErrorKind.INVALID_DATE_RANGE,
        -> ApiErrorType.KIS_BUSINESS_ERROR
    }

    fun isRetryable(kind: KisMarketErrorKind): Boolean = when (kind) {
        KisMarketErrorKind.NETWORK_TIMEOUT,
        KisMarketErrorKind.HTTP,
        -> true
        KisMarketErrorKind.AUTHENTICATION,
        KisMarketErrorKind.BUSINESS,
        KisMarketErrorKind.MALFORMED_RESPONSE,
        KisMarketErrorKind.INVALID_SYMBOL,
        KisMarketErrorKind.INVALID_DATE_RANGE,
        KisMarketErrorKind.MAPPING_FAILURE,
        -> false
    }
}
