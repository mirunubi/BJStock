package com.mirunubi.bjstock.core.forward

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartSequenceTest {
    private val calls = mutableListOf<String>()

    @Test
    fun recoveryRunsBeforeAutoScheduleReconciliation_thenMaintenance() = runBlocking {
        runAppStartSequence(
            recoverInterruptedOperations = { calls += "recover" },
            reconcileAutoSchedule = { calls += "schedule" },
            maintenance = { calls += "maintenance" },
        )

        assertEquals(listOf("recover", "schedule", "maintenance"), calls)
    }

    @Test
    fun recoveryFailure_stillReconcilesScheduleExactlyOnce_andRunsMaintenance() = runBlocking {
        runAppStartSequence(
            recoverInterruptedOperations = {
                calls += "recover"
                throw IllegalStateException("RUNNING_OPERATION_ALREADY_FINISHED")
            },
            reconcileAutoSchedule = { calls += "schedule" },
            maintenance = { calls += "maintenance" },
        )

        assertEquals(listOf("recover", "schedule", "maintenance"), calls)
    }

    @Test
    fun maintenanceFailure_isContained() = runBlocking {
        runAppStartSequence(
            recoverInterruptedOperations = { calls += "recover" },
            reconcileAutoSchedule = { calls += "schedule" },
            maintenance = { throw IllegalStateException("cleanup failed") },
        )

        assertEquals(listOf("recover", "schedule"), calls)
    }

    @Test
    fun scheduleReconciliationFailure_isContained_andMaintenanceStillRuns() = runBlocking {
        runAppStartSequence(
            recoverInterruptedOperations = { calls += "recover" },
            reconcileAutoSchedule = {
                calls += "schedule"
                throw IllegalStateException("WorkManager unavailable")
            },
            maintenance = { calls += "maintenance" },
        )

        assertEquals(listOf("recover", "schedule", "maintenance"), calls)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val failure = runCatching {
            runBlocking {
                runAppStartSequence(
                    recoverInterruptedOperations = { throw CancellationException("stopped") },
                    reconcileAutoSchedule = { calls += "schedule" },
                    maintenance = { calls += "maintenance" },
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(emptyList<String>(), calls)
    }
}
