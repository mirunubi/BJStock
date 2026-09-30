package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.kis.KisAuthErrorKind

enum class KisMarketErrorKind {
    AUTHENTICATION,
    HTTP,
    BUSINESS,
    RATE_LIMITED,
    NETWORK_TIMEOUT,
    MALFORMED_RESPONSE,
    INVALID_SYMBOL,
    INVALID_DATE_RANGE,
    MAPPING_FAILURE,
    UNEXPECTED,
}

data class KisMarketErrorAudit(
    val msgCd: String? = null,
    val httpCode: Int? = null,
    val msg1: String? = null,
    val rtCd: String? = null,
)

/**
 * [authKind] is set only for [KisMarketErrorKind.AUTHENTICATION] raised before the market call
 * (token issuance or missing credentials) and then decides the canonical code.
 */
class KisMarketException(
    val kind: KisMarketErrorKind,
    val publicMessage: String,
    val audit: KisMarketErrorAudit? = null,
    val authKind: KisAuthErrorKind? = null,
) : Exception(publicMessage)
