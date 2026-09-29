package com.mirunubi.bjstock.core.audit

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Carries the active `forward_operations.id` through the Forward Test call chain so that
 * `api_error_logs` / `trade_audit_logs` rows written inside the operation correlate to it
 * (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §7).
 */
class ForwardOperationContext(val operationId: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ForwardOperationContext>
}

suspend fun currentForwardOperationId(): Long? =
    currentCoroutineContext()[ForwardOperationContext]?.operationId
