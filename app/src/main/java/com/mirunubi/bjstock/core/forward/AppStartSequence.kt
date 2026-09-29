package com.mirunubi.bjstock.core.forward

import kotlinx.coroutines.CancellationException

/**
 * Process-start order (docs/150 §20.8): close operations interrupted by an earlier process, then re-apply the
 * persisted Auto schedule, then background maintenance. A recovery failure never prevents schedule
 * reconciliation. Recovery is bounded by the process-start cutoff, so work already started by this process
 * (for example a Worker launched by WorkManager during startup) is never touched.
 */
suspend fun runAppStartSequence(
    recoverInterruptedOperations: suspend () -> Unit,
    reconcileAutoSchedule: () -> Unit,
    maintenance: suspend () -> Unit,
) {
    runIgnoringFailure(recoverInterruptedOperations)
    reconcileAutoSchedule()
    runIgnoringFailure(maintenance)
}

private suspend fun runIgnoringFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}
