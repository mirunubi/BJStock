package com.mirunubi.bjstock.core.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.IntegrityViolationException
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Process-death recovery of `forward_operations` (docs/150 §20.8). Instants are injected; nothing waits on the
 * wall clock. Failures are injected with temporary SQLite triggers so production code needs no test hooks.
 */
@RunWith(RobolectricTestRunner::class)
class InterruptedOperationRecoveryTest {
    private lateinit var database: BJStockDatabase
    private lateinit var service: ForwardOperationLogService
    private var now: Instant = PREVIOUS_PROCESS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ForwardOperationLogService(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runningOperationBeforeCutoff_becomesFailedProcessInterrupted_withFinishEventAndNullElapsed() = runBlocking {
        val id = startedAt(PREVIOUS_PROCESS, "old")

        now = RECOVERY
        assertEquals(listOf(id), service.recoverInterruptedOperations(CUTOFF))

        val op = service.findOperation(id)!!
        assertEquals(ForwardOperationStatus.FAILED, op.status)
        assertEquals(ForwardOutcomeReason.PROCESS_INTERRUPTED.name, op.finalCode)
        assertEquals(MESSAGE, op.safeMessage)
        assertEquals(RECOVERY, op.finishedAt)
        assertEquals(PREVIOUS_PROCESS, op.startedAt)
        assertNull(op.elapsedMs)

        val events = service.findEvents(id)
        assertEquals(
            listOf(OperationalEventType.OPERATION_STARTED, OperationalEventType.OPERATION_FINISHED),
            events.map { it.eventType },
        )
        val finished = events.last()
        assertEquals("op:$id:finished", finished.eventKey)
        assertEquals(ForwardOperationStatus.FAILED.name, finished.result)
        assertEquals(ForwardOutcomeReason.PROCESS_INTERRUPTED.name, finished.reasonCode)
        assertEquals(MESSAGE, finished.safeMessage)
        assertNull(finished.elapsedMs)
        assertEquals(RECOVERY, finished.createdAt)
        assertEquals(THROUGH, finished.marketDate)
    }

    @Test
    fun storedCounts_arePreserved_notFabricated() = runBlocking {
        val id = database.forwardOperationDao().insert(
            ForwardOperationEntity(
                operationKey = "manual:counts",
                trigger = ForwardOperationTrigger.MANUAL,
                throughDate = THROUGH,
                status = ForwardOperationStatus.RUNNING,
                startedAt = PREVIOUS_PROCESS,
                runsConsidered = 3,
                runsProcessed = 1,
                runsSkipped = 1,
                cyclesCompleted = 2,
                cyclesFailed = 1,
            ),
        )

        now = RECOVERY
        service.recoverInterruptedOperations(CUTOFF)

        val op = service.findOperation(id)!!
        assertEquals(ForwardOperationStatus.FAILED, op.status)
        assertEquals(listOf(3, 1, 1, 2, 1), listOf(op.runsConsidered, op.runsProcessed, op.runsSkipped, op.cyclesCompleted, op.cyclesFailed))
        assertNull(op.elapsedMs)

        val fresh = startedAt(PREVIOUS_PROCESS, "zero")
        now = RECOVERY
        service.recoverInterruptedOperations(CUTOFF)
        val zero = service.findOperation(fresh)!!
        assertEquals(listOf(0, 0, 0, 0, 0), listOf(zero.runsConsidered, zero.runsProcessed, zero.runsSkipped, zero.cyclesCompleted, zero.cyclesFailed))
    }

    @Test
    fun terminalOperations_areUntouched() = runBlocking {
        val succeeded = startedAt(PREVIOUS_PROCESS, "done")
        service.finishOperation(succeeded, ForwardOperationStatus.SUCCEEDED, OperationCounts(runsConsidered = 1, runsProcessed = 1))
        val blocked = startedAt(PREVIOUS_PROCESS, "blocked")
        service.finishOperation(blocked, ForwardOperationStatus.BLOCKED, OperationCounts(), finalCode = "ALREADY_RUNNING")
        val rowsBefore = database.forwardOperationDao().findRecent()
        val eventsBefore = allEvents()

        now = RECOVERY
        assertEquals(emptyList<Long>(), service.recoverInterruptedOperations(CUTOFF))

        assertEquals(rowsBefore, database.forwardOperationDao().findRecent())
        assertEquals(eventsBefore, allEvents())
    }

    @Test
    fun runningAtOrAfterCutoff_isUntouched_strictComparison() = runBlocking {
        val before = startedAt(CUTOFF.minusMillis(1), "before")
        val exactly = startedAt(CUTOFF, "exactly")
        val after = startedAt(CUTOFF.plusMillis(1), "after")

        now = RECOVERY
        assertEquals(listOf(before), service.recoverInterruptedOperations(CUTOFF))

        assertEquals(ForwardOperationStatus.FAILED, service.findOperation(before)!!.status)
        listOf(exactly, after).forEach { id ->
            val op = service.findOperation(id)!!
            assertEquals(ForwardOperationStatus.RUNNING, op.status)
            assertNull(op.finishedAt)
            assertNull(op.finalCode)
            assertEquals(listOf(OperationalEventType.OPERATION_STARTED), service.findEvents(id).map { it.eventType })
        }
    }

    @Test
    fun secondReconciliation_isNoOp_withoutDuplicateFinishEvent() = runBlocking {
        val id = startedAt(PREVIOUS_PROCESS, "twice")
        now = RECOVERY
        service.recoverInterruptedOperations(CUTOFF)
        val rowAfterFirst = service.findOperation(id)
        val eventsAfterFirst = allEvents()

        now = RECOVERY.plusSeconds(60)
        assertEquals(emptyList<Long>(), service.recoverInterruptedOperations(CUTOFF.plusSeconds(60)))

        assertEquals(rowAfterFirst, service.findOperation(id))
        assertEquals(eventsAfterFirst, allEvents())
        assertEquals(1, finishedCount(id))
    }

    @Test
    fun multipleOrphans_allRecover_eachWithOneFinishEvent() = runBlocking {
        val manual = startedAt(PREVIOUS_PROCESS, "m")
        now = PREVIOUS_PROCESS.plusSeconds(1)
        val worker = service.startOperation(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.worker("w-1", THROUGH, 0),
                trigger = ForwardOperationTrigger.WORKER,
                throughDate = THROUGH,
                workId = "w-1",
                workAttempt = 0,
            ),
        ).operationId
        now = PREVIOUS_PROCESS.plusSeconds(2)
        val retry = service.startOperation(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.manualRetry("r-1"),
                trigger = ForwardOperationTrigger.MANUAL,
                throughDate = THROUGH,
                kind = ForwardOperationKind.RETRY_FAILED_CYCLE,
            ),
        ).operationId

        now = RECOVERY
        assertEquals(listOf(manual, worker, retry), service.recoverInterruptedOperations(CUTOFF))

        listOf(manual, worker, retry).forEach { id ->
            val op = service.findOperation(id)!!
            assertEquals(ForwardOperationStatus.FAILED, op.status)
            assertEquals(ForwardOutcomeReason.PROCESS_INTERRUPTED.name, op.finalCode)
            assertNull(op.elapsedMs)
            assertEquals(1, finishedCount(id))
        }
    }

    @Test
    fun finishEventFailure_rollsBackEveryOperationTransition_thenRetrySucceeds() = runBlocking {
        val first = startedAt(PREVIOUS_PROCESS, "a")
        val second = startedAt(PREVIOUS_PROCESS.plusSeconds(1), "b")
        sql(
            "CREATE TEMP TRIGGER inject_finish_failure BEFORE INSERT ON operational_events " +
                "WHEN NEW.event_type = 'OPERATION_FINISHED' AND NEW.operation_id = $second " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )

        now = RECOVERY
        assertNotNull(runCatching { service.recoverInterruptedOperations(CUTOFF) }.exceptionOrNull())
        assertStillRunningWithoutFinish(first, second)

        sql("DROP TRIGGER inject_finish_failure")
        assertEquals(listOf(first, second), service.recoverInterruptedOperations(CUTOFF))
        assertEquals(1, finishedCount(first))
        assertEquals(1, finishedCount(second))
    }

    @Test
    fun operationUpdateFailure_createsNoFinishEvent() = runBlocking {
        val first = startedAt(PREVIOUS_PROCESS, "a")
        val second = startedAt(PREVIOUS_PROCESS.plusSeconds(1), "b")
        sql(
            "CREATE TEMP TRIGGER inject_update_failure BEFORE UPDATE ON forward_operations " +
                "WHEN OLD.id = $second BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )

        now = RECOVERY
        assertNotNull(runCatching { service.recoverInterruptedOperations(CUTOFF) }.exceptionOrNull())
        assertStillRunningWithoutFinish(first, second)
        sql("DROP TRIGGER inject_update_failure")
    }

    @Test
    fun runningWithExistingFinishEvent_isAnIntegrityViolation_andNothingChanges() = runBlocking {
        val inconsistent = startedAt(PREVIOUS_PROCESS, "bad")
        val clean = startedAt(PREVIOUS_PROCESS.plusSeconds(1), "clean")
        database.operationalEventDao().insert(
            OperationalEventEntity(
                eventKey = OperationalEventKeys.operationFinished(inconsistent),
                operationId = inconsistent,
                eventType = OperationalEventType.OPERATION_FINISHED,
                result = ForwardOperationStatus.SUCCEEDED.name,
                elapsedMs = 5,
                createdAt = PREVIOUS_PROCESS,
            ),
        )
        val rowsBefore = database.forwardOperationDao().findRecent()
        val eventsBefore = allEvents()

        now = RECOVERY
        val failure = runCatching { service.recoverInterruptedOperations(CUTOFF) }.exceptionOrNull()

        assertTrue(failure is IntegrityViolationException)
        failure as IntegrityViolationException
        assertEquals(AppErrorCode.DATA_INTEGRITY_ERROR, failure.code)
        assertEquals("RUNNING_OPERATION_ALREADY_FINISHED", failure.reasonCode)
        assertEquals(rowsBefore, database.forwardOperationDao().findRecent())
        assertEquals(eventsBefore, allEvents())
        assertEquals(0, finishedCount(clean))
    }

    @Test
    fun priorPartialEvents_remainUnchanged_andNoCycleFinishedIsFabricated() = runBlocking {
        val id = startedAt(PREVIOUS_PROCESS, "partial")
        now = PREVIOUS_PROCESS.plusSeconds(1)
        service.appendOperationalEvent(
            OperationalEventInput(
                eventKey = OperationalEventKeys.marketSyncResult(id, 7),
                eventType = OperationalEventType.MARKET_SYNC_RESULT,
                operationId = id,
                runId = 7,
                result = "SUCCESS",
                elapsedMs = 120,
            ),
        )
        service.appendOperationalEvent(
            OperationalEventInput(
                eventKey = OperationalEventKeys.cycleStarted(id, 3, 1),
                eventType = OperationalEventType.CYCLE_STARTED,
                operationId = id,
                runId = 7,
                cycleId = 3,
                safeMessage = "Attempt 1",
            ),
        )
        val eventsBefore = service.findEvents(id)

        now = RECOVERY
        service.recoverInterruptedOperations(CUTOFF)

        val eventsAfter = service.findEvents(id)
        assertEquals(eventsBefore, eventsAfter.dropLast(1))
        assertEquals(OperationalEventType.OPERATION_FINISHED, eventsAfter.last().eventType)
        assertEquals(0, eventsAfter.count { it.eventType == OperationalEventType.CYCLE_FINISHED })
        assertEquals(0, eventsAfter.count { it.eventType == OperationalEventType.RUN_RESULT })
    }

    private suspend fun startedAt(at: Instant, requestId: String): Long {
        now = at
        return service.startOperation(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.manual(requestId),
                trigger = ForwardOperationTrigger.MANUAL,
                throughDate = THROUGH,
            ),
        ).operationId
    }

    private suspend fun assertStillRunningWithoutFinish(vararg ids: Long) {
        ids.forEach { id ->
            val op = service.findOperation(id)!!
            assertEquals(ForwardOperationStatus.RUNNING, op.status)
            assertNull(op.finishedAt)
            assertNull(op.finalCode)
            assertEquals(0, finishedCount(id))
        }
    }

    private suspend fun finishedCount(id: Long) =
        database.operationalEventDao().countByOperationAndType(id, OperationalEventType.OPERATION_FINISHED)

    private suspend fun allEvents(): List<OperationalEventEntity> =
        database.forwardOperationDao().findRecent(100).flatMap { service.findEvents(it.id) }.sortedBy { it.id }

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)

    private companion object {
        val THROUGH: LocalDate = LocalDate.of(2026, 9, 29)
        val PREVIOUS_PROCESS: Instant = Instant.parse("2026-09-29T22:30:00Z")
        val CUTOFF: Instant = Instant.parse("2026-09-29T23:00:00Z")
        val RECOVERY: Instant = Instant.parse("2026-09-29T23:00:02Z")
        const val MESSAGE = "Operation was interrupted before completion and recovered on app start"
    }
}
