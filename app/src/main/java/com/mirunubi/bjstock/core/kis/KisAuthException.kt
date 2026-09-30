package com.mirunubi.bjstock.core.kis

/**
 * Typed OAuth token failure (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §20.10).
 * Consumers classify by [kind] only; [KisAuthException.httpCode] is diagnostic metadata.
 */
enum class KisAuthErrorKind {
    CREDENTIAL_MISSING,
    CREDENTIAL_REJECTED,
    AUTH_REQUIRED,
    SERVER_ERROR,
    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    MALFORMED_RESPONSE,
    UNEXPECTED,
    ;

    companion object {
        fun fromTokenHttpStatus(httpCode: Int): KisAuthErrorKind = when (httpCode) {
            401, 403 -> CREDENTIAL_REJECTED
            in 500..599 -> SERVER_ERROR
            else -> AUTH_REQUIRED
        }
    }
}

class KisAuthException(
    val kind: KisAuthErrorKind,
    val publicMessage: String,
    val httpCode: Int? = null,
) : Exception(publicMessage)
