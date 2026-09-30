package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkRequest
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Gate 7B: daily one-time Auto scheduling, legacy migration, Worker ordering and schedule events. */
@RunWith(RobolectricTestRunner::class)
class ForwardAutoScheduleTest {
    private lateinit var database: BJStockDatabase
    private lateinit var settings: ForwardTestSchedulerSettings
    private lateinit var gateway: FakeAutoWorkGateway
    private lateinit var scheduler: ForwardTestScheduler
    private lateinit var operationLog: ForwardOperationLogService
    private val clock = MutableClock(kst(2026, 10, 1, 6, 0))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = ForwardTestSchedulerSettings(context)
        settings.setAutoEnabled(false)
        gateway = FakeAutoWorkGateway()
        operationLog = ForwardOperationLogService(database) { clock.instant() }
        scheduler = ForwardTestScheduler(settings, gateway, operationLog, ForwardTestClock(clock))
    }

    @After
    fun tearDown() {
        settings.setAutoEnabled(false)
        database.close()
    }

    // --- Auto ON / OFF ---

    @Test
    fun autoIsOffByDefault_andNothingIsScheduled() = runBlocking<Unit> {
        assertFalse(scheduler.isAutoEnabled())
        val status = scheduler.status()
        assertFalse(status.autoEnabled)
        assertNull(status.nextScheduleInstanceId)
        assertTrue(gateway.enqueueCalls.isEmpty())
    }

    @Test
    fun autoOn_neverExecutes_andEnqueuesOnlyTheNextFutureSlot() = runBlocking<Unit> {
        val change = scheduler.setAutoEnabled(true)

        assertTrue(scheduler.isAutoEnabled())
        assertEquals(slot(2026, 10, 1), change.enqueuedSlot)
        assertEquals(listOf(slot(2026, 10, 1) to TimeUnit.MINUTES.toMillis(90)), gateway.enqueueCalls)
        assertEquals(0, database.forwardOperationDao().countAll())
    }

    @Test
    fun autoOnAfter0730_targetsTomorrow_withAPositiveDelay() = runBlocking<Unit> {
        clock.now = kst(2026, 10, 1, 7, 30)
        scheduler.setAutoEnabled(true)

        assertEquals(listOf(slot(2026, 10, 2) to TimeUnit.HOURS.toMillis(24)), gateway.enqueueCalls)
    }

    @Test
    fun repeatedAutoOn_andReconciliation_neverDuplicateTheSlot() = runBlocking<Unit> {
        scheduler.setAutoEnabled(true)
        scheduler.setAutoEnabled(true)
        scheduler.reconcileOnAppStart()
        scheduler.reconcileOnAppStart()

        assertEquals(listOf(slot(2026, 10, 1)), gateway.activeSlots)
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(listOf("schedule:slot:auto:2026-10-01:0730:KST:enqueued"), scheduleEventKeys())
    }

    @Test
    fun autoOff_cancelsAllV2Work_andLegacyPeriodicWork() = runBlocking<Unit> {
        scheduler.setAutoEnabled(true)
        gateway.legacyActive = true

        val change = scheduler.setAutoEnabled(false)

        assertFalse(scheduler.isAutoEnabled())
        assertTrue(change.autoWorkCancelled)
        assertTrue(change.legacyCancelled)
        assertTrue(gateway.activeSlots.isEmpty())
        assertFalse(gateway.legacyActive)
        assertEquals(1, gateway.cancelAllRequests)
        val disabled = scheduleEvents().single { it.result == ForwardTestScheduler.AUTO_DISABLED }
        assertNull(disabled.operationId)
        assertEquals(ForwardTestScheduler.AUTO_DISABLED_MESSAGE, disabled.safeMessage)
    }

    @Test
    fun manualRunNow_isUnaffectedByAuto() = runBlocking<Unit> {
        val coordinator = coordinator(RecordingExecutor())
        scheduler.setAutoEnabled(false)

        val manual = coordinator.runManualNow()

        assertEquals(ForwardOperationTrigger.MANUAL, operationLog.findOperation(manual.operationId)!!.trigger)
        assertTrue(gateway.enqueueCalls.isEmpty())
        scheduler.setAutoEnabled(true)
        assertEquals(1, database.forwardOperationDao().countAll())
    }

    // --- app start ---

    @Test
    fun appStart_autoOff_enqueuesNothing_butStillCancelsLegacyAndV2() = runBlocking<Unit> {
        gateway.legacyActive = true

        scheduler.reconcileOnAppStart()

        assertTrue(gateway.enqueueCalls.isEmpty())
        assertEquals(1, gateway.legacyCancelRequests)
        assertEquals(1, gateway.cancelAllRequests)
        assertFalse(gateway.legacyActive)
        assertEquals(
            listOf("schedule:legacy:bjstock_forward_test_v1:cancelled"),
            scheduleEventKeys(),
        )
    }

    @Test
    fun appStart_autoOff_withNothingPending_recordsNoEvent() = runBlocking<Unit> {
        scheduler.reconcileOnAppStart()
        scheduler.reconcileOnAppStart()

        assertTrue(scheduleEventKeys().isEmpty())
    }

    @Test
    fun appStart_autoOn_preservesExistingFutureWork() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        gateway.enqueueSlot(slot(2026, 10, 1), 1L)
        clock.now = kst(2026, 10, 1, 7, 0)

        scheduler.reconcileOnAppStart()

        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(listOf(slot(2026, 10, 1)), gateway.activeSlots)
    }

    @Test
    fun appStart_autoOn_preservesRunningWork_withoutDuplicating() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        gateway.enqueueSlot(slot(2026, 10, 1), 1L)
        gateway.markRunning(slot(2026, 10, 1))
        clock.now = kst(2026, 10, 1, 7, 30, 1)

        scheduler.reconcileOnAppStart()

        assertEquals(1, gateway.enqueueCalls.size)
    }

    @Test
    fun appStart_autoOn_createsTheNextSlotWhenNoneExists_andMigratesLegacy() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        gateway.legacyActive = true

        val change = scheduler.reconcileOnAppStart()

        assertEquals(slot(2026, 10, 1), change.enqueuedSlot)
        assertTrue(change.legacyCancelled)
        assertEquals(listOf("cancelLegacy", "enqueue:auto:2026-10-01:0730:KST"), gateway.calls)
    }

    // --- legacy Worker ---

    @Test
    fun legacyInvocation_isSuppressed_createsNoOperation_andEnsuresV2BeforeCancellingLegacy() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        gateway.legacyActive = true
        var executed = false

        val disposition = runAutoInvocation("legacy-work", 0, Data.EMPTY, scheduler) { _, _, _ ->
            executed = true
            error("legacy input must never execute")
        }

        assertEquals(WorkerDisposition.SUCCESS, disposition)
        assertFalse(executed)
        assertEquals(0, database.forwardOperationDao().countAll())
        assertEquals(listOf("enqueue:auto:2026-10-01:0730:KST", "cancelLegacy"), gateway.calls)
        assertEquals(listOf(slot(2026, 10, 1)), gateway.activeSlots)
    }

    @Test
    fun legacyInvocation_withAutoOff_doesNothing() = runBlocking<Unit> {
        val disposition = runAutoInvocation("legacy-work", 0, Data.EMPTY, scheduler) { _, _, _ ->
            error("must not execute")
        }

        assertEquals(WorkerDisposition.SUCCESS, disposition)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun malformedScheduleInput_isTreatedAsLegacy_andNeverInventsAnId() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        val mismatched = Data.Builder()
            .putString(AutoWorkRequests.KEY_SCHEDULE_INSTANCE_ID, "auto:2026-10-01:0730:KST")
            .putLong(AutoWorkRequests.KEY_SCHEDULED_AT_EPOCH_MILLIS, 1L)
            .build()
        val idOnly = Data.Builder().putString(AutoWorkRequests.KEY_SCHEDULE_INSTANCE_ID, "auto:2026-10-01:0730:KST").build()
        val garbage = AutoWorkRequests.inputData(slot(2026, 10, 1)).let {
            Data.Builder().putAll(it).putString(AutoWorkRequests.KEY_SCHEDULE_INSTANCE_ID, "2026-10-01").build()
        }

        listOf(mismatched, idOnly, garbage).forEach { input ->
            assertNull(AutoWorkRequests.slotOf(input))
            assertEquals(
                WorkerDisposition.SUCCESS,
                runAutoInvocation("w", 0, input, scheduler) { _, _, _ -> error("must not execute") },
            )
        }
        assertEquals(0, database.forwardOperationDao().countAll())
    }

    // --- v2 Worker ---

    @Test
    fun v2Invocation_schedulesTheNextSlotBeforeExecuting() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        val current = slot(2026, 10, 1)
        gateway.enqueueSlot(current, 1L)
        gateway.markRunning(current)
        clock.now = kst(2026, 10, 1, 7, 30, 5)
        var slotsAtExecution: List<AutoScheduleSlot> = emptyList()

        runAutoInvocation("w-1", 0, AutoWorkRequests.inputData(current), scheduler) { _, _, sid ->
            assertEquals(current.scheduleInstanceId, sid)
            slotsAtExecution = gateway.activeSlots
            outcome(WorkerDisposition.SUCCESS)
        }

        assertEquals(listOf(current, slot(2026, 10, 2)), slotsAtExecution)
    }

    @Test
    fun success_failure_andRetry_eachLeaveExactlyOneNextSlot() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        val current = slot(2026, 10, 1)
        clock.now = kst(2026, 10, 1, 7, 31)
        listOf(WorkerDisposition.SUCCESS, WorkerDisposition.FAILURE, WorkerDisposition.RETRY).forEach { result ->
            val disposition = runAutoInvocation("w-1", 0, AutoWorkRequests.inputData(current), scheduler) { _, _, _ ->
                outcome(result)
            }
            assertEquals(result, disposition)
            assertEquals(listOf(slot(2026, 10, 2)), futureSlots())
        }
        runAutoInvocation("w-1", 1, AutoWorkRequests.inputData(current), scheduler) { _, attempt, sid ->
            assertEquals(1, attempt)
            assertEquals(current.scheduleInstanceId, sid)
            outcome(WorkerDisposition.SUCCESS)
        }
        assertEquals(listOf(slot(2026, 10, 2)), futureSlots())
    }

    @Test
    fun delayedInvocation_keepsItsSlotId_andSchedulesOnlyTheNextFutureSlot() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        val late = slot(2026, 9, 27)
        clock.now = kst(2026, 10, 1, 10, 0)
        var executedSid: String? = null

        runAutoInvocation("w-late", 0, AutoWorkRequests.inputData(late), scheduler) { _, _, sid ->
            executedSid = sid
            outcome(WorkerDisposition.SUCCESS)
        }

        assertEquals("auto:2026-09-27:0730:KST", executedSid)
        assertEquals(listOf(slot(2026, 10, 2)), gateway.enqueueCalls.map { it.first })
    }

    @Test
    fun delayedInvocation_catchUpUsesTheActualThroughDate_notTheSlotDate() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        val late = slot(2026, 9, 27)
        clock.now = kst(2026, 10, 1, 20, 0)
        val executor = RecordingExecutor()
        val coordinator = coordinator(executor)

        runAutoInvocation("w-late", 0, AutoWorkRequests.inputData(late), scheduler, coordinator::runWorker)

        val row = database.forwardOperationDao().findRecent().single()
        assertEquals("worker:auto:2026-09-27:0730:KST:0", row.operationKey)
        assertEquals("auto:2026-09-27:0730:KST", row.scheduleInstanceId)
        assertEquals(LocalDate.of(2026, 10, 1), row.throughDate)
        assertEquals(listOf(LocalDate.of(2026, 10, 1)), executor.throughDates)
    }

    @Test
    fun autoTurnedOffWhileWorkIsPending_invocationExitsWithoutOperation() = runBlocking<Unit> {
        val disposition = runAutoInvocation("w-1", 0, AutoWorkRequests.inputData(slot(2026, 10, 1)), scheduler) { _, _, _ ->
            error("must not execute")
        }

        assertEquals(WorkerDisposition.SUCCESS, disposition)
        assertTrue(gateway.calls.isEmpty())
        assertEquals(0, database.forwardOperationDao().countAll())
    }

    @Test
    fun cancellation_propagates_andTheNextSlotStaysScheduled() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        clock.now = kst(2026, 10, 1, 7, 30, 1)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runAutoInvocation("w-1", 0, AutoWorkRequests.inputData(slot(2026, 10, 1)), scheduler) { _, _, _ ->
                    throw CancellationException("stopped by WorkManager")
                }
            }
        }
        assertEquals(listOf(slot(2026, 10, 2)), futureSlots())
    }

    @Test
    fun processInterruptedReplay_retries_andTheNextAttemptKeepsTheSlot() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        clock.now = kst(2026, 10, 1, 7, 30, 1)
        val sid = slot(2026, 10, 1).scheduleInstanceId
        val coordinator = coordinator(RecordingExecutor())
        operationLog.startOperation(
            com.mirunubi.bjstock.core.audit.StartOperationRequest(
                operationKey = "worker:$sid:0",
                trigger = ForwardOperationTrigger.WORKER,
                throughDate = LocalDate.of(2026, 9, 30),
                workId = "w-1",
                workAttempt = 0,
                scheduleInstanceId = sid,
            ),
        )
        operationLog.recoverInterruptedOperations(clock.instant().plusMillis(1))

        val first = runAutoInvocation("w-1", 0, AutoWorkRequests.inputData(slot(2026, 10, 1)), scheduler, coordinator::runWorker)
        val second = runAutoInvocation("w-1", 1, AutoWorkRequests.inputData(slot(2026, 10, 1)), scheduler, coordinator::runWorker)

        assertEquals(WorkerDisposition.RETRY, first)
        assertEquals(WorkerDisposition.SUCCESS, second)
        val rows = database.forwardOperationDao().findRecent().sortedBy { it.id }
        assertEquals(listOf("worker:$sid:0", "worker:$sid:1"), rows.map { it.operationKey })
        assertEquals(listOf(sid, sid), rows.map { it.scheduleInstanceId })
        assertEquals(listOf(ForwardOperationStatus.FAILED, ForwardOperationStatus.NO_OP), rows.map { it.status })
    }

    // --- schedule events ---

    @Test
    fun slotEnqueuedEvent_isDeterministic_withSlotMarketDate_andNoOperation() = runBlocking<Unit> {
        scheduler.setAutoEnabled(true)

        val event = database.operationalEventDao().findByEventKey("schedule:slot:auto:2026-10-01:0730:KST:enqueued")!!
        assertEquals(OperationalEventType.WORKER_SCHEDULE_CHANGED, event.eventType)
        assertNull(event.operationId)
        assertEquals(LocalDate.of(2026, 10, 1), event.marketDate)
        assertEquals(ForwardTestScheduler.SLOT_ENQUEUED, event.result)
        assertEquals(ForwardTestScheduler.SLOT_ENQUEUED_MESSAGE, event.safeMessage)
    }

    @Test
    fun legacyCancellationEvent_isRecordedOnce() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        gateway.legacyActive = true
        scheduler.reconcileOnAppStart()
        gateway.legacyActive = true
        scheduler.reconcileOnAppStart()

        assertEquals(
            1,
            scheduleEventKeys().count { it == "schedule:legacy:bjstock_forward_test_v1:cancelled" },
        )
    }

    @Test
    fun scheduleEvents_containNoSecrets() = runBlocking<Unit> {
        gateway.legacyActive = true
        scheduler.setAutoEnabled(true)
        scheduler.setAutoEnabled(false)
        val forbidden = listOf("secret", "token", "bearer", "authorization", "appkey", "12345678", "eyj")

        val rows = scheduleEvents()
        assertEquals(3, rows.size)
        rows.forEach { row ->
            val text = "${row.eventKey} ${row.result} ${row.reasonCode} ${row.safeMessage}".lowercase()
            forbidden.forEach { assertFalse("persisted '$it'", text.contains(it)) }
        }
    }

    @Test
    fun eventPersistenceFailure_isSurfaced_andNeverUndoesScheduling() = runBlocking<Unit> {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER inject_schedule_event_failure BEFORE INSERT ON operational_events " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )

        val change = scheduler.setAutoEnabled(true)

        assertEquals(slot(2026, 10, 1), change.enqueuedSlot)
        assertEquals(1, change.eventFailures.size)
        assertTrue(change.eventFailures.single().startsWith(ForwardTestScheduler.SCHEDULE_EVENT_NOT_PERSISTED))
        assertEquals(listOf(slot(2026, 10, 1)), gateway.activeSlots)
        assertEquals(0, gateway.cancelAllRequests)
        assertTrue(scheduler.status().lastScheduleFailure!!.startsWith(ForwardTestScheduler.SCHEDULE_EVENT_NOT_PERSISTED))
        assertTrue(scheduleEventKeys().isEmpty())
    }

    @Test
    fun workManagerFailure_propagates_isVisibleInStatus_andTheFlagStaysAuthoritative() = runBlocking<Unit> {
        gateway.failEnqueue = IllegalStateException("unavailable")

        assertThrows(IllegalStateException::class.java) { runBlocking { scheduler.setAutoEnabled(true) } }

        assertTrue(scheduler.isAutoEnabled())
        assertTrue(scheduler.status().lastScheduleFailure!!.startsWith(ForwardTestScheduler.SCHEDULE_UPDATE_FAILED))
        gateway.failEnqueue = null
        scheduler.reconcileOnAppStart()
        assertEquals(listOf(slot(2026, 10, 1)), gateway.activeSlots)
    }

    // --- read model ---

    @Test
    fun status_exposesTheNextQueuedSlot_withoutParsingNamesOrKeys() = runBlocking<Unit> {
        settings.setAutoEnabled(true)
        gateway.enqueueSlot(slot(2026, 10, 1), 1L)
        gateway.markRunning(slot(2026, 10, 1))
        clock.now = kst(2026, 10, 1, 7, 30, 1)
        scheduler.ensureSlotAfter(slot(2026, 10, 1))

        val status = scheduler.status()

        assertTrue(status.autoEnabled)
        assertEquals("auto:2026-10-02:0730:KST", status.nextScheduleInstanceId)
        assertEquals(Instant.parse("2026-10-01T22:30:00Z"), status.nextScheduledAt)
        assertEquals("ENQUEUED", status.workState)
        assertEquals(gateway.pending.last().workId, status.workId)
        assertNull(status.lastScheduleFailure)
    }

    // --- request specification ---

    @Test
    fun request_isOneTime_delayed_networkConstrained_tagged_withDefaultBackoff() {
        val slot = slot(2026, 10, 1)
        val request = AutoWorkRequests.build(slot, TimeUnit.MINUTES.toMillis(90))
        val spec = request.workSpec

        assertFalse(spec.isPeriodic)
        assertEquals(ForwardTestWorker::class.java.name, spec.workerClassName)
        assertEquals(TimeUnit.MINUTES.toMillis(90), spec.initialDelay)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, spec.backoffDelayDuration)
        assertTrue(ForwardTestConfig.AUTO_WORK_TAG in request.tags)
        assertEquals(
            listOf("auto:2026-10-01:0730:KST"),
            request.tags.mapNotNull(AutoWorkRequests::scheduleInstanceIdFromTag),
        )
        assertEquals("auto:2026-10-01:0730:KST", spec.input.getString(AutoWorkRequests.KEY_SCHEDULE_INSTANCE_ID))
        assertEquals(slot.scheduledAt.toEpochMilli(), spec.input.getLong(AutoWorkRequests.KEY_SCHEDULED_AT_EPOCH_MILLIS, 0L))
        assertEquals(slot, AutoWorkRequests.slotOf(spec.input))
    }

    @Test
    fun request_refusesImmediateExecution() {
        listOf(0L, -1L).forEach { delay ->
            assertThrows(IllegalArgumentException::class.java) { AutoWorkRequests.build(slot(2026, 10, 1), delay) }
        }
    }

    // --- helpers ---

    private fun futureSlots(): List<AutoScheduleSlot> =
        gateway.activeSlots.filter { it.scheduledAt.isAfter(clock.instant()) }

    private fun coordinator(executor: ForwardRunExecutor) = ForwardTestExecutionCoordinator(
        executor = executor,
        operationLog = operationLog,
        clock = ForwardTestClock(clock),
    )

    private fun outcome(disposition: WorkerDisposition) = ForwardOperationOutcome(
        operationId = 1L,
        status = ForwardOperationStatus.SUCCEEDED,
        finalCode = null,
        display = ForwardOrchestratorResult.NoOp("test"),
        disposition = disposition,
    )

    private fun scheduleEvents() = database.operationalEventDao().let { dao ->
        scheduleEventKeys().map { runBlocking { dao.findByEventKey(it)!! } }
    }

    private fun scheduleEventKeys(): List<String> {
        val keys = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT event_key FROM operational_events WHERE event_type = 'WORKER_SCHEDULE_CHANGED' ORDER BY id")
            .use { while (it.moveToNext()) keys += it.getString(0) }
        return keys
    }

    private class RecordingExecutor : ForwardRunExecutor {
        val throughDates = mutableListOf<LocalDate>()

        override suspend fun executeForwardRuns(
            operationThroughDate: LocalDate,
            observer: ForwardExecutionObserver,
        ): ForwardExecutionReport {
            throughDates += operationThroughDate
            observer.onRunsSelected(emptyList())
            return ForwardExecutionReport(emptyList())
        }

        override suspend fun executeRetryFailedCycle(
            target: RetryFailedCycleTarget,
            operationThroughDate: LocalDate,
            observer: ForwardExecutionObserver,
        ): ForwardExecutionReport = ForwardExecutionReport(emptyList())
    }

    class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private companion object {
        fun slot(year: Int, month: Int, day: Int) = AutoScheduleSlot(LocalDate.of(year, month, day))

        fun kst(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Instant =
            ZonedDateTime.of(year, month, day, hour, minute, second, 0, ForwardTestConfig.MARKET_ZONE).toInstant()
    }
}
