package com.mirunubi.bjstock.core.error

/**
 * Canonical error catalog (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §4.1).
 * Only codes emitted by existing Forward Test / KIS / paper paths, plus the two system codes.
 * Persisted by [name]; never by ordinal.
 */
enum class AppErrorCode(
    val category: ErrorCategory,
    val severity: ErrorSeverity,
    val retryPolicy: RetryPolicy,
    val userActionRequired: Boolean,
    val operationAction: OperationAction,
    val auditRequired: Boolean,
    val safeMessage: String,
) {
    NO_POSITION_TO_SELL(
        ErrorCategory.DOMAIN, ErrorSeverity.INFO, RetryPolicy.NONE, false,
        OperationAction.SKIP, true,
        "No open position to sell; order skipped",
    ),
    POSITION_ALREADY_OPEN(
        ErrorCategory.DOMAIN, ErrorSeverity.INFO, RetryPolicy.NONE, false,
        OperationAction.SKIP, true,
        "Position already open; additional buy skipped",
    ),
    INSUFFICIENT_CASH(
        ErrorCategory.DOMAIN, ErrorSeverity.WARNING, RetryPolicy.NONE, false,
        OperationAction.SKIP, true,
        "Insufficient virtual cash; order rejected",
    ),
    INSUFFICIENT_WARMUP_DATA(
        ErrorCategory.DOMAIN, ErrorSeverity.WARNING, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "Not enough market history to start this run",
    ),
    INVALID_RUN_STATE(
        ErrorCategory.DOMAIN, ErrorSeverity.ERROR, RetryPolicy.NONE, false,
        OperationAction.SKIP, false,
        "Strategy run is not in a processable state",
    ),
    EMPTY_UNIVERSE(
        ErrorCategory.DOMAIN, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "Strategy run universe is empty",
    ),
    MISSING_TRADING_POLICY(
        ErrorCategory.DOMAIN, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "Strategy run has no paper trading policy",
    ),
    SNAPSHOT_MISSING_PRICE(
        ErrorCategory.DOMAIN, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "Snapshot price missing for a held instrument",
    ),
    CYCLE_FAILED(
        ErrorCategory.DOMAIN, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "A failed cycle blocks this run until retried",
    ),
    KIS_SERVER_ERROR(
        ErrorCategory.EXTERNAL, ErrorSeverity.DEGRADED, RetryPolicy.EXPONENTIAL_BACKOFF, false,
        OperationAction.RETRY_OPERATION, false,
        "KIS server error",
    ),
    KIS_BUSINESS_ERROR(
        ErrorCategory.EXTERNAL, ErrorSeverity.ERROR, RetryPolicy.NONE, false,
        OperationAction.BLOCK_RUN, false,
        "KIS rejected the request",
    ),
    KIS_MALFORMED_RESPONSE(
        ErrorCategory.EXTERNAL, ErrorSeverity.ERROR, RetryPolicy.NONE, false,
        OperationAction.BLOCK_RUN, false,
        "KIS response could not be read",
    ),
    NETWORK_UNAVAILABLE(
        ErrorCategory.TRANSIENT, ErrorSeverity.DEGRADED, RetryPolicy.EXPONENTIAL_BACKOFF, false,
        OperationAction.RETRY_OPERATION, false,
        "Network unavailable",
    ),
    NETWORK_TIMEOUT(
        ErrorCategory.TRANSIENT, ErrorSeverity.DEGRADED, RetryPolicy.EXPONENTIAL_BACKOFF, false,
        OperationAction.RETRY_OPERATION, false,
        "Network request timed out",
    ),
    KIS_RATE_LIMIT(
        ErrorCategory.TRANSIENT, ErrorSeverity.DEGRADED, RetryPolicy.FIXED_DELAY, false,
        OperationAction.RETRY_OPERATION, false,
        "KIS rate limit reached",
    ),
    CREDENTIAL_MISSING(
        ErrorCategory.SECURITY, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "KIS connection settings are not configured",
    ),
    CREDENTIAL_REJECTED(
        ErrorCategory.SECURITY, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "KIS rejected the configured connection settings",
    ),
    AUTH_REQUIRED(
        ErrorCategory.SECURITY, ErrorSeverity.ERROR, RetryPolicy.USER_ACTION_REQUIRED, true,
        OperationAction.REQUIRE_USER_ACTION, false,
        "KIS authentication required",
    ),
    DATA_INTEGRITY_ERROR(
        ErrorCategory.INVARIANT, ErrorSeverity.CRITICAL, RetryPolicy.NONE, true,
        OperationAction.ABORT_OPERATION, false,
        "Local data integrity check failed",
    ),
    INTERNAL_INVARIANT_VIOLATION(
        ErrorCategory.INVARIANT, ErrorSeverity.CRITICAL, RetryPolicy.NONE, true,
        OperationAction.ABORT_OPERATION, false,
        "Internal consistency check failed",
    ),
    UNEXPECTED_EXCEPTION(
        ErrorCategory.UNEXPECTED, ErrorSeverity.ERROR, RetryPolicy.NONE, false,
        OperationAction.ABORT_OPERATION, false,
        "Unexpected error",
    ),
    ;

    val isRetryableAutomatically: Boolean
        get() = retryPolicy == RetryPolicy.BOUNDED_IMMEDIATE ||
            retryPolicy == RetryPolicy.FIXED_DELAY ||
            retryPolicy == RetryPolicy.EXPONENTIAL_BACKOFF

    companion object {
        /** Unknown or null codes map to [UNEXPECTED_EXCEPTION]. */
        fun fromCode(code: String?): AppErrorCode =
            entries.firstOrNull { it.name == code } ?: UNEXPECTED_EXCEPTION
    }
}
