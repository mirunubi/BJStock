package com.mirunubi.bjstock.core.error

/** Canonical error categories. See docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §2. */
enum class ErrorCategory {
    DOMAIN,
    EXTERNAL,
    TRANSIENT,
    SECURITY,
    INVARIANT,
    UNEXPECTED,
}

/** Ascending severity; [FINANCIAL_INTEGRITY] is the highest. §3. */
enum class ErrorSeverity {
    INFO,
    WARNING,
    DEGRADED,
    ERROR,
    CRITICAL,
    FINANCIAL_INTEGRITY,
}

/** Centralized retry classes. Functions must not invent their own retry rules. §5. */
enum class RetryPolicy {
    NONE,
    BOUNDED_IMMEDIATE,
    FIXED_DELAY,
    EXPONENTIAL_BACKOFF,
    USER_ACTION_REQUIRED,
}

/** What the orchestrator does when a code is raised. §4. */
enum class OperationAction {
    CONTINUE,
    SKIP,
    BLOCK_RUN,
    RETRY_OPERATION,
    ABORT_OPERATION,
    REQUIRE_USER_ACTION,
}
