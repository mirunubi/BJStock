package com.mirunubi.bjstock.core.error

/**
 * Typed abort for a canonical key that already exists with different content, a filled order whose
 * execution is missing, or a ledger whose arithmetic does not reconcile (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §11–§12). Never caught to continue.
 * [severity] always equals the catalog severity of [code], so FINANCIAL_INTEGRITY cannot be downgraded.
 */
class IntegrityViolationException(
    val code: AppErrorCode,
    val reasonCode: String,
) : IllegalStateException(reasonCode) {
    val severity: ErrorSeverity get() = code.severity

    init {
        require(code.category == ErrorCategory.INVARIANT) { "integrity violations use INVARIANT codes" }
        require(SafeLogText.isCode(reasonCode)) { "reasonCode must be a canonical token" }
    }

    companion object {
        fun ledgerMismatch(reasonCode: String) =
            IntegrityViolationException(AppErrorCode.LEDGER_MISMATCH, reasonCode)

        fun executionConflict(reasonCode: String) =
            IntegrityViolationException(AppErrorCode.EXECUTION_IDEMPOTENCY_CONFLICT, reasonCode)

        fun filledOrderWithoutExecution() =
            IntegrityViolationException(AppErrorCode.FILLED_ORDER_WITHOUT_EXECUTION, "FILLED_ORDER_WITHOUT_EXECUTION")

        fun invariant(reasonCode: String) =
            IntegrityViolationException(AppErrorCode.INTERNAL_INVARIANT_VIOLATION, reasonCode)
    }
}
