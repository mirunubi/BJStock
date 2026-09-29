package com.mirunubi.bjstock.core.error

/**
 * Typed abort for a canonical key that already exists with different content
 * (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §11–§12). Never caught to continue.
 * [severity] is [ErrorSeverity.FINANCIAL_INTEGRITY] for execution / cash ledger conflicts.
 */
class IntegrityViolationException(
    val code: AppErrorCode,
    val severity: ErrorSeverity,
    val reasonCode: String,
) : IllegalStateException(reasonCode) {
    init {
        require(code.category == ErrorCategory.INVARIANT) { "integrity violations use INVARIANT codes" }
        require(SafeLogText.isCode(reasonCode)) { "reasonCode must be a canonical token" }
    }

    companion object {
        fun financial(reasonCode: String) = IntegrityViolationException(
            code = AppErrorCode.DATA_INTEGRITY_ERROR,
            severity = ErrorSeverity.FINANCIAL_INTEGRITY,
            reasonCode = reasonCode,
        )

        fun invariant(reasonCode: String) = IntegrityViolationException(
            code = AppErrorCode.INTERNAL_INVARIANT_VIOLATION,
            severity = ErrorSeverity.CRITICAL,
            reasonCode = reasonCode,
        )
    }
}
