package com.mirunubi.bjstock.core.error

/**
 * Allowlisted diagnostic context. Never carries [Throwable.message],
 * headers, bodies, credentials, or tokens.
 */
data class SafeDiagnostics(
    val exceptionType: String? = null,
    val httpStatus: Int? = null,
    val businessCode: String? = null,
    val logicalEndpoint: String? = null,
    val attempt: Int? = null,
) {
    init {
        require(exceptionType == null || SafeLogText.exceptionType(exceptionType) != null) {
            "exceptionType must be a simple class name"
        }
        require(businessCode == null || SafeLogText.businessCode(businessCode) != null) {
            "businessCode has a disallowed shape"
        }
        require(logicalEndpoint == null || SafeLogText.isCode(logicalEndpoint)) {
            "logicalEndpoint must be a canonical token"
        }
        require(httpStatus == null || httpStatus in 100..599) { "httpStatus out of range" }
        require(attempt == null || attempt >= 0) { "attempt must be non-negative" }
    }

    companion object {
        val NONE = SafeDiagnostics()
    }
}

/** Canonical application error with a safe message and allowlisted diagnostics. */
data class SafeAppError(
    val code: AppErrorCode,
    val diagnostics: SafeDiagnostics = SafeDiagnostics.NONE,
    private val messageOverride: String? = null,
) {
    val safeMessage: String = SafeLogText.message(messageOverride)
        ?.takeUnless { it == SafeLogText.WITHHELD }
        ?: code.safeMessage

    val category: ErrorCategory get() = code.category
    val severity: ErrorSeverity get() = code.severity
    val retryPolicy: RetryPolicy get() = code.retryPolicy
    val operationAction: OperationAction get() = code.operationAction
    val userActionRequired: Boolean get() = code.userActionRequired

    companion object {
        fun of(code: AppErrorCode, diagnostics: SafeDiagnostics = SafeDiagnostics.NONE) =
            SafeAppError(code, diagnostics)

        /**
         * Translates any throwable into a canonical error.
         * [kotlinx.coroutines.CancellationException] is rethrown, never converted.
         */
        fun fromThrowable(
            throwable: Throwable,
            logicalEndpoint: String? = null,
            attempt: Int? = null,
        ): SafeAppError = AppErrorMapper.fromThrowable(throwable, logicalEndpoint, attempt)
    }
}
