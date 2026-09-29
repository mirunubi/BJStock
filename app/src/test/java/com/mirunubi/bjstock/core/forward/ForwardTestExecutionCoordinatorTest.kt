package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.audit.ForwardOperationKeys
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.audit.StartOperationRequest
import com.mirunubi.bjstock.core.audit.currentForwardOperationId
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.ForwardRunResult
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coordinator contract with a scripted executor: operation rows, lifecycle events, single-flight,
 * key semantics, cancellation, and safe failure. Gates are [CompletableDeferred]s; nothing sleeps.
 */
@RunWith(RobolectricTestRunner::class)
class ForwardTestExecutionCoordinatorTest {
    private lateinit var database: BJStockDatabase
    private lateinit var operationLog: ForwardOperationLogService
    private lateinit var executor: ScriptedExecutor
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: ForwardTestExecutionCoordinator
    private val requestIds = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        operationLog = ForwardOperationLogService(database) { NOW }
        executor = ScriptedExecutor()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = coordinator(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    // --- operation rows, triggers, kinds ---

    @Test
    fun manualRunNow_createsOneManualForwardRunRow() = runBlocking<Unit> {
        val outcome = coordinator.runManualNow()

        val op = onlyOperation()
        assertEquals(outcome.operationId, op.id)
        assertEquals("manual:req-1", op.operationKey)
        assertEquals(ForwardOperationTrigger.MANUAL, op.trigger)
        assertEquals(ForwardOperationKind.FORWARD_RUN, op.operationKind)
        assertNull(op.workId)
        assertNull(op.workAttempt)
        assertEquals(THROUGH, op.throughDate)
        assertEquals(listOf(THROUGH), executor.forwardDates)
        assertEquals(1, executor.forwardCalls.get())
    }

    @Test
    fun worker_createsOneWorkerRow_withWorkIdentityAndInterimKey() = runBlocking<Unit> {
        coordinator.runWorker(workId = WORK_ID, runAttempt = 2)

        val op = onlyOperation()
        assertEquals("worker:$WORK_ID:2026-09-30:2", op.operationKey)
        assertEquals(ForwardOperationTrigger.WORKER, op.trigger)
        assertEquals(ForwardOperationKind.FORWARD_RUN, op.operationKind)
        assertEquals(WORK_ID, op.workId)
        assertEquals(2, op.workAttempt)
        assertEquals(THROUGH, op.throughDate)
    }

    @Test
    fun startedAndFinishedEvents_bracketTheOperation() = runBlocking<Unit> {
        executor.forward = { _, observer -> reportOf(observer, processed(1)) }
        val outcome = coordinator.runManualNow()

        val events = operationLog.findEvents(outcome.operationId)
        assertEquals(OperationalEventType.OPERATION_STARTED, events.first().eventType)
        assertEquals("op:${outcome.operationId}:started", events.first().eventKey)
        assertEquals("MANUAL", events.first().result)
        assertEquals(OperationalEventType.OPERATION_FINISHED, events.last().eventType)
        assertEquals("op:${outcome.operationId}:finished", events.last().eventKey)
        assertEquals("SUCCEEDED", events.last().result)
        assertEquals(1, events.count { it.eventType == OperationalEventType.OPERATION_STARTED })
        assertEquals(1, events.count { it.eventType == OperationalEventType.OPERATION_FINISHED })
    }

    @Test
    fun success_persistsAggregateCounts() = runBlocking<Unit> {
        executor.forward = { _, observer ->
            reportOf(observer, processed(1, cycles = 2), processed(2, cycles = 1), skipped(3))
        }
        val outcome = coordinator.runManualNow()

        val op = onlyOperation()
        assertEquals(ForwardOperationStatus.SUCCEEDED, outcome.status)
        assertEquals(ForwardOperationStatus.SUCCEEDED, op.status)
        assertEquals(3, op.runsConsidered)
        assertEquals(2, op.runsProcessed)
        assertEquals(1, op.runsSkipped)
        assertEquals(3, op.cyclesCompleted)
        assertEquals(0, op.cyclesFailed)
        assertNull(op.finalCode)
        assertEquals("Processed 2 of 3 run(s); skipped 1; cycles completed 3, failed 0", op.safeMessage)
        assertNotNull(op.finishedAt)
        assertEquals(WorkerDisposition.SUCCESS, outcome.disposition)
    }

    @Test
    fun noEligibleRuns_isNoOp_andWorkerSucceeds() = runBlocking<Unit> {
        val outcome = coordinator.runWorker(WORK_ID, 0)

        val op = onlyOperation()
        assertEquals(ForwardOperationStatus.NO_OP, op.status)
        assertEquals(ForwardOutcomeReason.NO_ELIGIBLE_RUNS.name, op.finalCode)
        assertEquals(0, op.runsConsidered)
        assertEquals(WorkerDisposition.SUCCESS, outcome.disposition)
        assertTrue(outcome.display is ForwardOrchestratorResult.NoOp)
    }

    @Test
    fun nonRetryableBlock_isBlocked_andWorkerFails() = runBlocking<Unit> {
        executor.forward = { _, observer -> reportOf(observer, blocked(1, ForwardOutcomeReason.MISSING_POLICY.name)) }
        val outcome = coordinator.runWorker(WORK_ID, 0)

        val op = onlyOperation()
        assertEquals(ForwardOperationStatus.BLOCKED, op.status)
        assertEquals(ForwardOutcomeReason.MISSING_POLICY.name, op.finalCode)
        assertEquals(WorkerDisposition.FAILURE, outcome.disposition)
    }

    @Test
    fun retryableBlock_isBlocked_andWorkerRetries() = runBlocking<Unit> {
        executor.forward = { _, observer ->
            reportOf(observer, blocked(1, ForwardErrorCode.NETWORK_FAILURE.name, retryable = true))
        }
        val outcome = coordinator.runWorker(WORK_ID, 0)

        assertEquals(ForwardOperationStatus.BLOCKED, onlyOperation().status)
        assertEquals(WorkerDisposition.RETRY, outcome.disposition)
    }

    @Test
    fun unexpectedException_persistsOnlySafeRepresentation_andWorkerFails() = runBlocking<Unit> {
        executor.forward = { _, _ -> throw IllegalStateException(SECRET_BEARING_TEXT) }
        val outcome = coordinator.runWorker(WORK_ID, 0)

        val op = onlyOperation()
        assertEquals(ForwardOperationStatus.FAILED, op.status)
        assertEquals("UNEXPECTED_EXCEPTION", op.finalCode)
        assertTrue(op.safeMessage!!.endsWith("(IllegalStateException)"))
        assertEquals(WorkerDisposition.FAILURE, outcome.disposition)
        assertFalse((outcome.display as ForwardOrchestratorResult.Blocked).errorMessage.contains("TEST_"))
        assertFalse(coordinator.isExecutionActive)
        assertNoSecretPersisted()
    }

    @Test
    fun cancellation_isRethrown_recordedAsCancelled_andReleasesLock() = runBlocking<Unit> {
        val gate = Gate()
        executor.forward = { _, _ -> gate.hold() }
        val worker = async(Dispatchers.Default) { coordinator.runWorker(WORK_ID, 0) }
        gate.awaitEntered()
        assertTrue(coordinator.isExecutionActive)

        worker.cancel()
        try {
            withTimeout(TIMEOUT) { worker.await() }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            // rethrown to the caller
        }

        val op = onlyOperation()
        assertEquals(ForwardOperationStatus.FAILED, op.status)
        assertEquals(ForwardOutcomeReason.CANCELLED.name, op.finalCode)
        assertEquals("Operation cancelled before completion", op.safeMessage)
        assertFalse(coordinator.isExecutionActive)
        executor.forward = { _, observer -> reportOf(observer) }
        assertEquals(ForwardOperationStatus.NO_OP, coordinator.runManualNow().status)
    }

    // --- single-flight ---

    @Test
    fun manualManualOverlap_secondIsDurableAlreadyRunning() = runBlocking<Unit> {
        val gate = Gate()
        executor.forward = { _, observer -> gate.hold(); reportOf(observer, processed(1)) }
        val first = async { coordinator.runManualNow() }
        gate.awaitEntered()

        val second = coordinator.runManualNow()

        assertAlreadyRunning(second, ForwardOperationTrigger.MANUAL, ForwardOperationKind.FORWARD_RUN)
        assertEquals(WorkerDisposition.RETRY, second.disposition)
        gate.release()
        assertEquals(ForwardOperationStatus.SUCCEEDED, withTimeout(TIMEOUT) { first.await() }.status)
        assertEquals(1, executor.forwardCalls.get())
        assertEquals(2, database.forwardOperationDao().countAll())
    }

    @Test
    fun manualWorkerOverlap_isBlockedInBothDirections() = runBlocking<Unit> {
        val gate = Gate()
        executor.forward = { _, observer -> gate.hold(); reportOf(observer, processed(1)) }
        val manual = async { coordinator.runManualNow() }
        gate.awaitEntered()
        val workerWhileManual = coordinator.runWorker(WORK_ID, 0)
        assertAlreadyRunning(workerWhileManual, ForwardOperationTrigger.WORKER, ForwardOperationKind.FORWARD_RUN)
        assertEquals(WorkerDisposition.RETRY, workerWhileManual.disposition)
        gate.release()
        withTimeout(TIMEOUT) { manual.await() }

        val secondGate = Gate()
        executor.forward = { _, observer -> secondGate.hold(); reportOf(observer, processed(1)) }
        val worker = async(Dispatchers.Default) { coordinator.runWorker(WORK_ID, 1) }
        secondGate.awaitEntered()
        val manualWhileWorker = coordinator.runManualNow()
        assertAlreadyRunning(manualWhileWorker, ForwardOperationTrigger.MANUAL, ForwardOperationKind.FORWARD_RUN)
        secondGate.release()
        assertEquals(ForwardOperationStatus.SUCCEEDED, withTimeout(TIMEOUT) { worker.await() }.status)
        assertEquals(2, executor.forwardCalls.get())
    }

    @Test
    fun workerWorkerOverlap_secondIsBlocked() = runBlocking<Unit> {
        val gate = Gate()
        executor.forward = { _, observer -> gate.hold(); reportOf(observer, processed(1)) }
        val first = async(Dispatchers.Default) { coordinator.runWorker("work-a", 0) }
        gate.awaitEntered()

        val second = coordinator.runWorker("work-b", 0)

        assertAlreadyRunning(second, ForwardOperationTrigger.WORKER, ForwardOperationKind.FORWARD_RUN)
        assertEquals(WorkerDisposition.RETRY, second.disposition)
        gate.release()
        withTimeout(TIMEOUT) { first.await() }
        assertEquals(1, executor.forwardCalls.get())
    }

    @Test
    fun lockIsReleasedAfterSuccess() = runBlocking<Unit> {
        executor.forward = { _, observer -> reportOf(observer, processed(1)) }
        coordinator.runManualNow()
        assertFalse(coordinator.isExecutionActive)
        assertEquals(ForwardOperationStatus.SUCCEEDED, coordinator.runWorker(WORK_ID, 0).status)
        assertEquals(2, executor.forwardCalls.get())
    }

    @Test
    fun lockIsReleasedAfterFailure() = runBlocking<Unit> {
        executor.forward = { _, _ -> throw IllegalArgumentException("boom") }
        assertEquals(ForwardOperationStatus.FAILED, coordinator.runManualNow().status)
        assertFalse(coordinator.isExecutionActive)
        executor.forward = { _, observer -> reportOf(observer, processed(1)) }
        assertEquals(ForwardOperationStatus.SUCCEEDED, coordinator.runManualNow().status)
    }

    // --- key semantics / idempotency ---

    @Test
    fun workerRedelivery_sameWorkIdDateAttempt_resolvesToSameRowWithoutReexecution() = runBlocking<Unit> {
        executor.forward = { _, observer -> reportOf(observer, processed(1)) }
        val first = coordinator.runWorker(WORK_ID, 3)
        val replay = coordinator.runWorker(WORK_ID, 3)

        assertEquals(first.operationId, replay.operationId)
        assertTrue(replay.replayed)
        assertEquals(ForwardOperationStatus.SUCCEEDED, replay.status)
        assertEquals(WorkerDisposition.SUCCESS, replay.disposition)
        assertEquals(1, executor.forwardCalls.get())
        assertEquals(1, database.forwardOperationDao().countAll())
        assertEquals(
            listOf(
                OperationalEventType.OPERATION_STARTED,
                OperationalEventType.RUN_RESULT,
                OperationalEventType.OPERATION_FINISHED,
            ),
            operationLog.findEvents(first.operationId).map { it.eventType },
        )

        coordinator.runWorker(WORK_ID, 4)
        assertEquals(2, database.forwardOperationDao().countAll())
    }

    @Test
    fun workerRedelivery_ofFailedOperation_keepsStoredDisposition() = runBlocking<Unit> {
        executor.forward = { _, observer -> reportOf(observer, blocked(1, ForwardOutcomeReason.MISSING_POLICY.name)) }
        coordinator.runWorker(WORK_ID, 0)
        executor.forward = { _, observer -> reportOf(observer, processed(1)) }

        val replay = coordinator.runWorker(WORK_ID, 0)

        assertTrue(replay.replayed)
        assertEquals(ForwardOperationStatus.BLOCKED, replay.status)
        assertEquals(WorkerDisposition.FAILURE, replay.disposition)
        assertEquals(1, executor.forwardCalls.get())
    }

    // --- interrupted operation recovery (Gate 7A) ---

    @Test
    fun recoveryAlone_executesNothing_andStartsNoOperation() = runBlocking<Unit> {
        val orphan = startWorkerInPreviousProcess(attempt = 0)

        assertEquals(listOf(orphan), operationLog.recoverInterruptedOperations(NOW.plusMillis(1)))

        assertEquals(0, executor.forwardCalls.get())
        assertEquals(0, executor.retryCalls.get())
        assertFalse(coordinator.isExecutionActive)
        assertEquals(1, database.forwardOperationDao().countAll())
        assertEquals(ForwardOperationStatus.FAILED, onlyOperation().status)
    }

    @Test
    fun workerRedelivery_ofProcessInterruptedOperation_retries_andNextAttemptExecutesNormally() = runBlocking<Unit> {
        val orphan = startWorkerInPreviousProcess(attempt = 0)
        operationLog.recoverInterruptedOperations(NOW.plusMillis(1))

        val replay = coordinator.runWorker(WORK_ID, 0)

        assertTrue(replay.replayed)
        assertEquals(orphan, replay.operationId)
        assertEquals(ForwardOperationStatus.FAILED, replay.status)
        assertEquals(ForwardOutcomeReason.PROCESS_INTERRUPTED.name, replay.finalCode)
        assertEquals(WorkerDisposition.RETRY, replay.disposition)
        assertEquals(0, executor.forwardCalls.get())

        executor.forward = { _, observer -> reportOf(observer, processed(1)) }
        val next = coordinator.runWorker(WORK_ID, 1)

        assertFalse(next.replayed)
        assertEquals(ForwardOperationStatus.SUCCEEDED, next.status)
        assertEquals(WorkerDisposition.SUCCESS, next.disposition)
        assertEquals("worker:$WORK_ID:2026-09-30:1", operationLog.findOperation(next.operationId)!!.operationKey)
        assertEquals(listOf(THROUGH), executor.forwardDates)
        assertEquals(1, executor.forwardCalls.get())
    }

    @Test
    fun manualInvocation_afterRecovery_isUnaffected() = runBlocking<Unit> {
        startWorkerInPreviousProcess(attempt = 0)
        operationLog.recoverInterruptedOperations(NOW.plusMillis(1))
        executor.forward = { _, observer -> reportOf(observer, processed(1)) }

        val manual = coordinator.runManualNow()

        assertFalse(manual.replayed)
        assertEquals(ForwardOperationStatus.SUCCEEDED, manual.status)
        assertEquals("manual:req-1", operationLog.findOperation(manual.operationId)!!.operationKey)
        assertEquals(1, executor.forwardCalls.get())
        assertEquals(2, database.forwardOperationDao().countAll())
    }

    @Test
    fun workerRedelivery_ofStillRunningOperation_remainsRetry_andIsNotRecoveredByCurrentCutoff() = runBlocking<Unit> {
        val running = startWorkerInPreviousProcess(attempt = 0)

        assertEquals(emptyList<Long>(), operationLog.recoverInterruptedOperations(NOW))
        val replay = coordinator.runWorker(WORK_ID, 0)

        assertEquals(running, replay.operationId)
        assertEquals(ForwardOperationStatus.RUNNING, replay.status)
        assertEquals(WorkerDisposition.RETRY, replay.disposition)
        assertEquals(0, executor.forwardCalls.get())
    }

    @Test
    fun manualTaps_eachGetTheirOwnRow() = runBlocking<Unit> {
        coordinator.runManualNow()
        coordinator.runManualNow()
        val keys = database.forwardOperationDao().findRecent().map { it.operationKey }.sorted()
        assertEquals(listOf("manual:req-1", "manual:req-2"), keys)
    }

    // --- correlation context ---

    @Test
    fun executorRunsInsideOperationContext_andContextIsClearedAfterwards() = runBlocking<Unit> {
        val outcome = coordinator.runManualNow()
        val worker = coordinator.runWorker(WORK_ID, 0)

        assertEquals(listOf<Long?>(outcome.operationId, worker.operationId), executor.seenOperationIds)
        assertNull(currentForwardOperationId())
    }

    // --- aggregate / events from observer ---

    @Test
    fun observerEvents_arePersistedWithOperationCorrelation() = runBlocking<Unit> {
        executor.forward = { date, observer ->
            observer.onRunsSelected(listOf(7))
            observer.onMarketSync(7, date, MarketSyncOutcome(success = true, requestedStart = date, insertedCount = 2), 5)
            reportOf(observer, processed(7))
        }
        val outcome = coordinator.runManualNow()

        val events = operationLog.findEvents(outcome.operationId)
        val sync = events.single { it.eventType == OperationalEventType.MARKET_SYNC_RESULT }
        assertEquals("op:${outcome.operationId}:run:7:sync", sync.eventKey)
        assertEquals(7L, sync.runId)
        assertEquals(THROUGH, sync.marketDate)
        assertEquals("SUCCESS", sync.result)
        assertEquals("Requested 2026-09-30 to 2026-09-30; inserted 2, updated 0, unchanged 0", sync.safeMessage)
        assertEquals(5L, sync.elapsedMs)
        val run = events.single { it.eventType == OperationalEventType.RUN_RESULT }
        assertEquals("op:${outcome.operationId}:run:7:result", run.eventKey)
        assertEquals("PROCESSED", run.result)
        assertEquals(ForwardOutcomeReason.PROCESSED.name, run.reasonCode)
    }

    @Test
    fun partialFailure_countsRecordedProgressBeforeTheException() = runBlocking<Unit> {
        executor.forward = { _, observer ->
            observer.onRunsSelected(listOf(1, 2))
            observer.onRunResult(processed(1, cycles = 1))
            error("second run exploded")
        }
        coordinator.runManualNow()

        val op = onlyOperation()
        assertEquals(ForwardOperationStatus.FAILED, op.status)
        assertEquals(2, op.runsConsidered)
        assertEquals(1, op.runsProcessed)
    }

    // --- Retry Failed Cycle through the coordinator ---

    @Test
    fun retry_goesThroughCoordinator_asManualRetryFailedCycle_oneRowPerTap() = runBlocking<Unit> {
        val outcome = coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))

        assertEquals(1, executor.retryCalls.get())
        assertEquals(0, executor.forwardCalls.get())
        assertEquals(listOf(RetryFailedCycleTarget(runId = 5)), executor.retryTargets)
        val op = onlyOperation()
        assertEquals(outcome.operationId, op.id)
        assertEquals("manual-retry:req-1", op.operationKey)
        assertEquals(ForwardOperationTrigger.MANUAL, op.trigger)
        assertEquals(ForwardOperationKind.RETRY_FAILED_CYCLE, op.operationKind)
        assertEquals(ForwardOperationStatus.SUCCEEDED, op.status)
        assertEquals(listOf<Long?>(op.id), executor.seenOperationIds)
    }

    @Test
    fun retry_isBlockedWhileRunNowIsActive_withoutTouchingTheTarget() = runBlocking<Unit> {
        val gate = Gate()
        executor.forward = { _, observer -> gate.hold(); reportOf(observer, processed(1)) }
        val runNow = async { coordinator.runManualNow() }
        gate.awaitEntered()

        val retry = coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))

        assertAlreadyRunning(retry, ForwardOperationTrigger.MANUAL, ForwardOperationKind.RETRY_FAILED_CYCLE)
        assertEquals(0, executor.retryCalls.get())
        gate.release()
        withTimeout(TIMEOUT) { runNow.await() }
    }

    @Test
    fun retry_isBlockedWhileWorkerIsActive() = runBlocking<Unit> {
        val gate = Gate()
        executor.forward = { _, observer -> gate.hold(); reportOf(observer, processed(1)) }
        val worker = async(Dispatchers.Default) { coordinator.runWorker(WORK_ID, 0) }
        gate.awaitEntered()

        val retry = coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))

        assertAlreadyRunning(retry, ForwardOperationTrigger.MANUAL, ForwardOperationKind.RETRY_FAILED_CYCLE)
        assertEquals(0, executor.retryCalls.get())
        gate.release()
        withTimeout(TIMEOUT) { worker.await() }
    }

    @Test
    fun runNow_isBlockedWhileRetryIsActive() = runBlocking<Unit> {
        val gate = Gate()
        executor.retry = { target, _, observer -> gate.hold(); reportOf(observer, processed(target.runId)) }
        val retry = async { coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5)) }
        gate.awaitEntered()

        val runNow = coordinator.runManualNow()

        assertAlreadyRunning(runNow, ForwardOperationTrigger.MANUAL, ForwardOperationKind.FORWARD_RUN)
        assertEquals(0, executor.forwardCalls.get())
        gate.release()
        assertEquals(ForwardOperationStatus.SUCCEEDED, withTimeout(TIMEOUT) { retry.await() }.status)
    }

    @Test
    fun worker_isBlockedWhileRetryIsActive() = runBlocking<Unit> {
        val gate = Gate()
        executor.retry = { target, _, observer -> gate.hold(); reportOf(observer, processed(target.runId)) }
        val retry = async { coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5)) }
        gate.awaitEntered()

        val worker = coordinator.runWorker(WORK_ID, 0)

        assertAlreadyRunning(worker, ForwardOperationTrigger.WORKER, ForwardOperationKind.FORWARD_RUN)
        assertEquals(WorkerDisposition.RETRY, worker.disposition)
        gate.release()
        withTimeout(TIMEOUT) { retry.await() }
    }

    @Test
    fun retryRetryOverlap_secondIsBlocked() = runBlocking<Unit> {
        val gate = Gate()
        executor.retry = { target, _, observer -> gate.hold(); reportOf(observer, processed(target.runId)) }
        val first = async { coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5)) }
        gate.awaitEntered()

        val second = coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))

        assertAlreadyRunning(second, ForwardOperationTrigger.MANUAL, ForwardOperationKind.RETRY_FAILED_CYCLE)
        gate.release()
        withTimeout(TIMEOUT) { first.await() }
        assertEquals(1, executor.retryCalls.get())
    }

    @Test
    fun retry_lockIsReleasedAfterSuccess() = runBlocking<Unit> {
        coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))
        assertFalse(coordinator.isExecutionActive)
        assertEquals(ForwardOperationStatus.NO_OP, coordinator.runWorker(WORK_ID, 0).status)
    }

    @Test
    fun retry_lockIsReleasedAfterFailure() = runBlocking<Unit> {
        executor.retry = { _, _, _ -> throw IllegalStateException("retry exploded") }
        val outcome = coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))
        assertEquals(ForwardOperationStatus.FAILED, outcome.status)
        assertFalse(coordinator.isExecutionActive)
        assertEquals(ForwardOperationStatus.NO_OP, coordinator.runWorker(WORK_ID, 0).status)
    }

    @Test
    fun retry_lockIsReleasedAfterCancellation() = runBlocking<Unit> {
        val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retryCoordinator = coordinator(retryScope)
        val gate = Gate()
        executor.retry = { _, _, _ -> gate.hold() }
        val retry = async { retryCoordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5)) }
        gate.awaitEntered()

        retryScope.cancel()
        try {
            withTimeout(TIMEOUT) { retry.await() }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            // rethrown to the caller
        }

        val op = onlyOperation()
        assertEquals(ForwardOperationKind.RETRY_FAILED_CYCLE, op.operationKind)
        assertEquals(ForwardOperationStatus.FAILED, op.status)
        assertEquals(ForwardOutcomeReason.CANCELLED.name, op.finalCode)
        assertFalse(retryCoordinator.isExecutionActive)
        assertEquals(ForwardOperationStatus.NO_OP, retryCoordinator.runWorker(WORK_ID, 0).status)
    }

    @Test
    fun retry_secretBearingException_isNotPersisted() = runBlocking<Unit> {
        executor.retry = { _, _, _ -> throw IllegalStateException(SECRET_BEARING_TEXT) }
        val outcome = coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = 5))

        assertEquals("UNEXPECTED_EXCEPTION", outcome.finalCode)
        assertNoSecretPersisted()
    }

    // --- helpers ---

    private fun coordinator(executionScope: CoroutineScope) = ForwardTestExecutionCoordinator(
        executor = executor,
        operationLog = operationLog,
        clock = ForwardTestClock(Clock.fixed(CLOCK_INSTANT, ForwardTestConfig.MARKET_ZONE)),
        executionScope = executionScope,
        newRequestId = { "req-${requestIds.incrementAndGet()}" },
    )

    private suspend fun onlyOperation(): ForwardOperationEntity =
        database.forwardOperationDao().findRecent().single()

    /** A Worker operation that an earlier process started at [NOW] and never finished. */
    private suspend fun startWorkerInPreviousProcess(attempt: Int): Long =
        operationLog.startOperation(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.worker(WORK_ID, THROUGH, attempt),
                trigger = ForwardOperationTrigger.WORKER,
                throughDate = THROUGH,
                workId = WORK_ID,
                workAttempt = attempt,
            ),
        ).operationId

    private suspend fun assertAlreadyRunning(
        outcome: ForwardOperationOutcome,
        trigger: ForwardOperationTrigger,
        kind: ForwardOperationKind,
    ) {
        assertEquals(ForwardOperationStatus.BLOCKED, outcome.status)
        assertEquals(ForwardOutcomeReason.ALREADY_RUNNING.name, outcome.finalCode)
        assertFalse(outcome.replayed)
        val row = operationLog.findOperation(outcome.operationId)!!
        assertEquals(trigger, row.trigger)
        assertEquals(kind, row.operationKind)
        assertEquals(ForwardOperationStatus.BLOCKED, row.status)
        assertEquals(ForwardOutcomeReason.ALREADY_RUNNING.name, row.finalCode)
        assertEquals("Another Forward Test operation is already running", row.safeMessage)
        assertNotNull(row.finishedAt)
        val events = operationLog.findEvents(outcome.operationId).map { it.eventType }
        assertEquals(listOf(OperationalEventType.OPERATION_STARTED, OperationalEventType.OPERATION_FINISHED), events)
    }

    private fun assertNoSecretPersisted() {
        val forbidden = listOf("test_app_secret", "test_access_token", "bearer", "authorization", "12345678")
        val raw = database.openHelper.readableDatabase
        listOf(
            "SELECT operation_key || ' ' || COALESCE(final_code,'') || ' ' || COALESCE(safe_message,'') FROM forward_operations",
            "SELECT event_key || ' ' || COALESCE(result,'') || ' ' || COALESCE(reason_code,'') || ' ' || " +
                "COALESCE(safe_message,'') FROM operational_events",
        ).forEach { sql ->
            raw.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    val text = cursor.getString(0).lowercase()
                    forbidden.forEach { assertFalse("persisted '$it'", text.contains(it)) }
                }
            }
        }
    }

    private class Gate {
        private val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun hold(): Nothing? {
            entered.complete(Unit)
            released.await()
            return null
        }

        suspend fun awaitEntered() = withTimeout(TIMEOUT) { entered.await() }

        fun release() {
            released.complete(Unit)
        }
    }

    private class ScriptedExecutor : ForwardRunExecutor {
        var forward: suspend (LocalDate, ForwardExecutionObserver) -> ForwardExecutionReport? =
            { _, observer -> reportOf(observer) }
        var retry: suspend (RetryFailedCycleTarget, LocalDate, ForwardExecutionObserver) -> ForwardExecutionReport? =
            { target, _, observer -> reportOf(observer, processed(target.runId)) }
        val forwardCalls = AtomicInteger()
        val retryCalls = AtomicInteger()
        val forwardDates = CopyOnWriteArrayList<LocalDate>()
        val retryTargets = CopyOnWriteArrayList<RetryFailedCycleTarget>()
        val seenOperationIds = CopyOnWriteArrayList<Long?>()

        override suspend fun executeForwardRuns(
            operationThroughDate: LocalDate,
            observer: ForwardExecutionObserver,
        ): ForwardExecutionReport {
            forwardCalls.incrementAndGet()
            forwardDates += operationThroughDate
            seenOperationIds += currentForwardOperationId()
            return checkNotNull(forward(operationThroughDate, observer))
        }

        override suspend fun executeRetryFailedCycle(
            target: RetryFailedCycleTarget,
            operationThroughDate: LocalDate,
            observer: ForwardExecutionObserver,
        ): ForwardExecutionReport {
            retryCalls.incrementAndGet()
            retryTargets += target
            seenOperationIds += currentForwardOperationId()
            return checkNotNull(retry(target, operationThroughDate, observer))
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val WORK_ID = "7b0c2f55-1d2e-4a6b-9f3c-0d1e2f3a4b5c"
        val THROUGH: LocalDate = LocalDate.of(2026, 9, 30)
        val NOW: Instant = Instant.parse("2026-09-30T11:00:00Z")
        val CLOCK_INSTANT: Instant =
            ZonedDateTime.of(2026, 9, 30, 20, 0, 0, 0, ForwardTestConfig.MARKET_ZONE).toInstant()
        const val SECRET_BEARING_TEXT =
            "appsecret=TEST_APP_SECRET Authorization: Bearer TEST_ACCESS_TOKEN account 12345678-01"

        suspend fun reportOf(observer: ForwardExecutionObserver, vararg runs: ForwardRunReport): ForwardExecutionReport {
            observer.onRunsSelected(runs.map { it.runId })
            runs.forEach { observer.onRunResult(it) }
            return ForwardExecutionReport(runs.toList())
        }

        fun processed(runId: Long, cycles: Int = 1) = ForwardRunReport(
            runId = runId,
            result = ForwardRunResult.PROCESSED,
            reasonCode = ForwardOutcomeReason.PROCESSED.name,
            outcome = ForwardOrchestratorResult.Ok(List(cycles) { THROUGH.minusDays(it.toLong()) }),
            processingStarted = true,
            cyclesCompleted = cycles,
        )

        fun skipped(runId: Long) = ForwardRunReport(
            runId = runId,
            result = ForwardRunResult.SKIPPED,
            reasonCode = ForwardOutcomeReason.THROUGH_DATE_BEFORE_START.name,
            outcome = ForwardOrchestratorResult.NoOp("throughDate before start_date"),
        )

        fun blocked(runId: Long, code: String, retryable: Boolean = false) = ForwardRunReport(
            runId = runId,
            result = ForwardRunResult.BLOCKED,
            reasonCode = code,
            outcome = ForwardOrchestratorResult.Blocked(null, code, code, retryable),
        )
    }
}
