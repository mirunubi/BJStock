package com.mirunubi.bjstock.core.kis.market

enum class KisMarketErrorKind {
    AUTHENTICATION,
    HTTP,
    BUSINESS,
    NETWORK_TIMEOUT,
    MALFORMED_RESPONSE,
    INVALID_SYMBOL,
    INVALID_DATE_RANGE,
    MAPPING_FAILURE,
}

data class KisMarketErrorAudit(
    val msgCd: String? = null,
    val httpCode: Int? = null,
    val msg1: String? = null,
)

class KisMarketException(
    val kind: KisMarketErrorKind,
    val publicMessage: String,
    val audit: KisMarketErrorAudit? = null,
) : Exception(publicMessage)
