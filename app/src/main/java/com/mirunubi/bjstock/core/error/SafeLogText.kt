package com.mirunubi.bjstock.core.error

import com.mirunubi.bjstock.core.audit.ApiErrorLogService

/**
 * Allowlist validation for text persisted by operational logging
 * (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §17).
 * [ApiErrorLogService.sanitize] is also applied as a defence-in-depth deny-list.
 */
object SafeLogText {
    const val MAX_MESSAGE_LENGTH = 200
    const val WITHHELD = "details withheld"

    private val CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val EVENT_KEY = Regex("^[a-z]+(:[A-Za-z0-9_-]{1,64}){1,8}$")
    private val OPERATION_KEY = Regex(
        "^(worker:[A-Za-z0-9-]{1,64}:[0-9]{4}-[0-9]{2}-[0-9]{2}:[0-9]{1,6}" +
            "|manual:[A-Za-z0-9-]{1,64}" +
            "|manual-retry:[A-Za-z0-9-]{1,64})$",
    )
    private val BUSINESS_CODE = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val EXCEPTION_TYPE = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,79}$")

    private val MESSAGE_ALLOWED = Regex("^[\\p{L}\\p{N} .,:;()\\-_/%+#'!?\\[\\]]*$")
    private val OPAQUE_TOKEN = Regex("[A-Za-z0-9+/_-]{32,}")
    private val JWT_PREFIX = Regex("eyJ[A-Za-z0-9_-]{8,}")
    private val ACCOUNT_LIKE = Regex("\\d{8}-?\\d{2}|\\d{10,}")
    private val SECRET_KEYWORDS = listOf(
        "appkey", "app key", "app_key", "appsecret", "app secret", "app_secret",
        "secret", "token", "authorization", "bearer", "password", "passwd",
        "cookie", "credential", "account", "acctno", "header", "body",
    )

    fun isCode(value: String): Boolean = CODE.matches(value)

    fun code(value: String): String {
        require(isCode(value)) { "not a canonical code token" }
        return value
    }

    fun eventKey(value: String): String {
        require(EVENT_KEY.matches(value)) { "event_key has a disallowed shape" }
        return value
    }

    fun operationKey(value: String): String {
        require(OPERATION_KEY.matches(value)) { "operation_key has a disallowed shape" }
        return value
    }

    fun businessCode(value: String?): String? = value?.takeIf { BUSINESS_CODE.matches(it) }

    fun exceptionType(value: String?): String? = value?.takeIf { EXCEPTION_TYPE.matches(it) }

    /** Returns a persistable message, [WITHHELD] if the input is not provably safe, or null. */
    fun message(raw: String?): String? {
        if (raw == null) return null
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return null
        if (!isSafeMessage(normalized)) return WITHHELD
        return normalized
    }

    fun isSafeMessage(value: String): Boolean {
        if (value.length > MAX_MESSAGE_LENGTH) return false
        if (!MESSAGE_ALLOWED.matches(value)) return false
        val lowered = value.lowercase()
        if (SECRET_KEYWORDS.any { lowered.contains(it) }) return false
        if (OPAQUE_TOKEN.containsMatchIn(value)) return false
        if (JWT_PREFIX.containsMatchIn(value)) return false
        if (ACCOUNT_LIKE.containsMatchIn(value)) return false
        return ApiErrorLogService.sanitize(value) == value
    }
}
