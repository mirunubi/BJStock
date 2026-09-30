package com.mirunubi.bjstock.core.audit

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.SafeAppError
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

@RunWith(RobolectricTestRunner::class)
class ForwardOperationLogServiceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var service: ForwardOperationLogService
    private var now: Instant = Instant.parse("2026-09-29T22:30:00Z")
    private val throughDate = LocalDate.of(2026, 9, 29)

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
    fun startOperation_createsRunningRowAndStartedEvent() = runBlocking {
        val result = service.startOperation(workerRequest("w-1", 0))

        assertTrue(result is StartOperationResult.Started)
        val op = service.findOperation(result.operationId)!!
        assertEquals("worker:auto:2026-09-30:0730:KST:0", op.operationKey)
        assertEquals(ForwardOperationTrigger.WORKER, op.trigger)
        assertEquals(ForwardOperationKind.FORWARD_RUN, op.operationKind)
        assertEquals("w-1", op.workId)
        assertEquals(0, op.workAttempt)
        assertEquals(SLOT, op.scheduleInstanceId)
        assertEquals(throughDate, op.throughDate)
        assertEquals(ForwardOperationStatus.RUNNING, op.status)
        assertEquals(now, op.startedAt)
        assertNull(op.finishedAt)
        assertNull(op.elapsedMs)

        val events = service.findEvents(result.operationId)
        assertEquals(1, events.size)
        assertEquals(OperationalEventType.OPERATION_STARTED, events.single().eventType)
        assertEquals("op:${result.operationId}:started", events.single().eventKey)
        assertEquals("WORKER", events.single().result)
    }

    @Test
    fun startOperation_persistsUtcEpochMillis() = runBlocking {
        val id = service.startOperation(manualRequest("req-utc")).operationId
        database.openHelper.readableDatabase
            .query("SELECT started_at, through_date FROM forward_operations WHERE id = $id")
            .use {
                assertTrue(it.moveToFirst())
                assertEquals(now.toEpochMilli(), it.getLong(0))
                assertEquals(throughDate.toEpochDay(), it.getLong(1))
            }
    }

    @Test
    fun startOperation_replaySameKey_returnsExistingWithoutDuplicate() = runBlocking {
        val first = service.startOperation(manualRequest("req-1"))
        now = now.plusSeconds(5)
        val replay = service.startOperation(manualRequest("req-1"))

        assertTrue(replay is StartOperationResult.AlreadyExists)
        assertEquals(first.operationId, replay.operationId)
        assertEquals(1, database.forwardOperationDao().countAll())
        assertEquals(1, service.findEvents(first.operationId).size)
    }

    @Test
    fun operationKey_isUniqueAtDatabaseLevel() = runBlocking {
        service.startOperation(manualRequest("dup"))
        val raw = database.openHelper.writableDatabase
        assertThrows(SQLiteConstraintException::class.java) {
            raw.execSQL(
                """
                INSERT INTO forward_operations (
                    operation_key, `trigger`, through_date, status, started_at,
                    runs_considered, runs_processed, runs_skipped, cycles_completed, cycles_failed
                ) VALUES ('manual:dup', 'MANUAL', 0, 'RUNNING', 0, 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
        }
        assertEquals(1, database.forwardOperationDao().countAll())
    }

    @Test
    fun startOperation_rejectsInconsistentTriggerIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.startOperation(
                    StartOperationRequest("worker:$SLOT:0", ForwardOperationTrigger.WORKER, throughDate),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.startOperation(
                    StartOperationRequest(
                        operationKey = "worker:auto:2026-10-01:0730:KST:0",
                        trigger = ForwardOperationTrigger.WORKER,
                        throughDate = throughDate,
                        workId = "w-2",
                        workAttempt = 0,
                        scheduleInstanceId = SLOT,
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.startOperation(
                    StartOperationRequest(
                        operationKey = "manual:req-x",
                        trigger = ForwardOperationTrigger.MANUAL,
                        throughDate = throughDate,
                        workId = "w-3",
                        workAttempt = 0,
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.startOperation(
                    StartOperationRequest("manual:req y", ForwardOperationTrigger.MANUAL, throughDate),
                )
            }
        }
        runBlocking { assertEquals(0, database.forwardOperationDao().countAll()) }
    }

    @Test
    fun workerOperations_requireAValidScheduleInstance_andTheCanonicalKey() {
        fun request(key: String, sid: String?, attempt: Int = 0, workId: String? = "w-5") = StartOperationRequest(
            operationKey = key,
            trigger = ForwardOperationTrigger.WORKER,
            throughDate = throughDate,
            workId = workId,
            workAttempt = attempt,
            scheduleInstanceId = sid,
        )
        val rejected = listOf(
            request("worker:$SLOT:0", sid = null),
            request("worker:$SLOT:0", sid = " "),
            request("worker:auto:2026-9-30:0730:KST:0", sid = "auto:2026-9-30:0730:KST"),
            request("worker:auto:2026-09-30:0800:UTC:0", sid = "auto:2026-09-30:0800:UTC"),
            request("worker:w-5:2026-09-29:0", sid = SLOT),
            request("worker:$SLOT:1", sid = SLOT, attempt = 0),
            request("worker:$SLOT:-1", sid = SLOT, attempt = -1),
            request("worker:$SLOT:0", sid = SLOT, workId = " "),
        )
        rejected.forEach { assertThrows(IllegalArgumentException::class.java) { runBlocking { service.startOperation(it) } } }
        runBlocking { assertEquals(0, database.forwardOperationDao().countAll()) }
    }

    @Test
    fun manualOperations_neverCarryAScheduleInstance() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.startOperation(manualRequest("req-s").copy(scheduleInstanceId = SLOT))
            }
        }
        runBlocking {
            val id = service.startOperation(manualRequest("req-t")).operationId
            assertNull(service.findOperation(id)!!.scheduleInstanceId)
        }
    }

    @Test
    fun sameSlotAndAttempt_replaysIdempotently_whileANewAttemptIsANewOperation() = runBlocking {
        val first = service.startOperation(workerRequest("w-6", 0))
        val replay = service.startOperation(workerRequest("w-7", 0))
        val nextAttempt = service.startOperation(workerRequest("w-6", 1))

        assertTrue(replay is StartOperationResult.AlreadyExists)
        assertEquals(first.operationId, replay.operationId)
        assertTrue(nextAttempt is StartOperationResult.Started)
        val rows = database.forwardOperationDao().findRecent()
        assertEquals(2, rows.size)
        assertEquals(setOf(SLOT), rows.map { it.scheduleInstanceId }.toSet())
        assertEquals(setOf("worker:$SLOT:0", "worker:$SLOT:1"), rows.map { it.operationKey }.toSet())
    }

    @Test
    fun legacyWorkerRow_withoutScheduleInstance_staysReadable() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO forward_operations (
                operation_key, `trigger`, work_id, work_attempt, through_date, status, started_at, finished_at,
                runs_considered, runs_processed, runs_skipped, cycles_completed, cycles_failed
            ) VALUES ('worker:w-legacy:2026-09-29:0', 'WORKER', 'w-legacy', 0, 0, 'SUCCEEDED', 0, 1, 0, 0, 0, 0, 0)
            """.trimIndent(),
        )

        val legacy = database.forwardOperationDao().findRecent().single()
        assertEquals("worker:w-legacy:2026-09-29:0", legacy.operationKey)
        assertEquals(ForwardOperationTrigger.WORKER, legacy.trigger)
        assertNull(legacy.scheduleInstanceId)
    }

    @Test
    fun operationKind_isPersistedAndValidatedAgainstTriggerAndKey() = runBlocking {
        val retry = service.startOperation(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.manualRetry("req-r"),
                trigger = ForwardOperationTrigger.MANUAL,
                throughDate = throughDate,
                kind = ForwardOperationKind.RETRY_FAILED_CYCLE,
            ),
        )
        val op = service.findOperation(retry.operationId)!!
        assertEquals("manual-retry:req-r", op.operationKey)
        assertEquals(ForwardOperationTrigger.MANUAL, op.trigger)
        assertEquals(ForwardOperationKind.RETRY_FAILED_CYCLE, op.operationKind)
        assertEquals(
            ForwardOperationKind.FORWARD_RUN,
            service.findOperation(service.startOperation(manualRequest("req-f")).operationId)!!.operationKind,
        )

        val rejected = listOf(
            StartOperationRequest("manual:req-a", ForwardOperationTrigger.MANUAL, throughDate, kind = ForwardOperationKind.RETRY_FAILED_CYCLE),
            StartOperationRequest("manual-retry:req-b", ForwardOperationTrigger.MANUAL, throughDate),
            StartOperationRequest(
                operationKey = ForwardOperationKeys.worker(SLOT, 1),
                trigger = ForwardOperationTrigger.WORKER,
                throughDate = throughDate,
                workId = "w-9",
                workAttempt = 1,
                kind = ForwardOperationKind.RETRY_FAILED_CYCLE,
                scheduleInstanceId = SLOT,
            ),
        )
        rejected.forEach { request ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.startOperation(request) } }
        }
        assertEquals(2, database.forwardOperationDao().countAll())
    }

    @Test
    fun finishOperation_setsTerminalFieldsAndFinishedEvent() = runBlocking {
        val id = service.startOperation(workerRequest("w-4", 1)).operationId
        now = now.plusMillis(1_234)

        val result = service.finishOperation(
            operationId = id,
            status = ForwardOperationStatus.PARTIAL,
            counts = OperationCounts(
                runsConsidered = 3,
                runsProcessed = 2,
                runsSkipped = 1,
                cyclesCompleted = 4,
                cyclesFailed = 1,
            ),
            finalCode = "NETWORK_TIMEOUT",
            safeMessage = "Run 2 market sync timed out",
        )

        assertEquals(FinishOperationResult.Finished(id, ForwardOperationStatus.PARTIAL), result)
        val op = service.findOperation(id)!!
        assertEquals(ForwardOperationStatus.PARTIAL, op.status)
        assertEquals(now, op.finishedAt)
        assertEquals(1_234L, op.elapsedMs)
        assertEquals("NETWORK_TIMEOUT", op.finalCode)
        assertEquals("Run 2 market sync timed out", op.safeMessage)
        assertEquals(3, op.runsConsidered)
        assertEquals(2, op.runsProcessed)
        assertEquals(1, op.runsSkipped)
        assertEquals(4, op.cyclesCompleted)
        assertEquals(1, op.cyclesFailed)

        val finished = service.findEvents(id).last()
        assertEquals(OperationalEventType.OPERATION_FINISHED, finished.eventType)
        assertEquals("op:$id:finished", finished.eventKey)
        assertEquals("PARTIAL", finished.result)
        assertEquals("NETWORK_TIMEOUT", finished.reasonCode)
        assertEquals(1_234L, finished.elapsedMs)
    }

    @Test
    fun finishOperation_replayDoesNotOverwriteTerminalRow() = runBlocking {
        val id = service.startOperation(manualRequest("req-2")).operationId
        service.finishOperation(id, ForwardOperationStatus.SUCCEEDED, OperationCounts(runsConsidered = 1))
        now = now.plusSeconds(60)

        val replay = service.finishOperation(
            id,
            ForwardOperationStatus.FAILED,
            OperationCounts(),
            finalCode = "UNEXPECTED_EXCEPTION",
        )

        assertEquals(FinishOperationResult.AlreadyFinished(id, ForwardOperationStatus.SUCCEEDED), replay)
        val op = service.findOperation(id)!!
        assertEquals(ForwardOperationStatus.SUCCEEDED, op.status)
        assertNull(op.finalCode)
        assertEquals(2, service.findEvents(id).size)
    }

    @Test
    fun finishOperation_rejectsRunningAndReportsMissing() = runBlocking {
        val id = service.startOperation(manualRequest("req-3")).operationId
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.finishOperation(id, ForwardOperationStatus.RUNNING, OperationCounts()) }
        }
        assertEquals(
            FinishOperationResult.NotFound(999),
            service.finishOperation(999, ForwardOperationStatus.FAILED, OperationCounts()),
        )
    }

    @Test
    fun finishOperation_withUnexpectedException_persistsOnlySafeRepresentation() = runBlocking {
        val id = service.startOperation(manualRequest("req-4")).operationId
        val error = SafeAppError.fromThrowable(
            IllegalStateException("appsecret=REAL-SECRET-VALUE Authorization: Bearer abc"),
        )

        service.finishOperation(id, ForwardOperationStatus.FAILED, OperationCounts(runsConsidered = 1), error)

        val op = service.findOperation(id)!!
        assertEquals("UNEXPECTED_EXCEPTION", op.finalCode)
        assertEquals(AppErrorCode.UNEXPECTED_EXCEPTION.safeMessage, op.safeMessage)
        assertNoSecretPersisted()
    }

    @Test
    fun appendOperationalEvent_persistsTypedFields() = runBlocking {
        val opId = service.startOperation(manualRequest("req-5")).operationId
        now = now.plusMillis(10)

        val result = service.appendOperationalEvent(
            OperationalEventInput(
                eventKey = OperationalEventKeys.runResult(opId, 3),
                eventType = OperationalEventType.RUN_RESULT,
                operationId = opId,
                runId = 3,
                marketDate = throughDate,
                result = "PROCESSED",
                reasonCode = null,
                safeMessage = "processed 1 market date",
                elapsedMs = 850,
            ),
        )

        assertTrue(result is AppendEventResult.Appended)
        val row = database.operationalEventDao().findByEventKey("op:$opId:run:3:result")!!
        assertEquals(result.eventId, row.id)
        assertEquals(opId, row.operationId)
        assertEquals(3L, row.runId)
        assertEquals(throughDate, row.marketDate)
        assertEquals(OperationalEventType.RUN_RESULT, row.eventType)
        assertEquals("PROCESSED", row.result)
        assertEquals("processed 1 market date", row.safeMessage)
        assertEquals(850L, row.elapsedMs)
        assertEquals(now, row.createdAt)
    }

    @Test
    fun appendOperationalEvent_replayIsIdempotentAndKeepsOriginal() = runBlocking {
        val opId = service.startOperation(manualRequest("req-6")).operationId
        val key = OperationalEventKeys.cycleFinished(opId, cycleId = 12, attempt = 1)
        val first = service.appendOperationalEvent(
            OperationalEventInput(
                eventKey = key,
                eventType = OperationalEventType.CYCLE_FINISHED,
                operationId = opId,
                cycleId = 12,
                result = "COMPLETE",
            ),
        )
        val replay = service.appendOperationalEvent(
            OperationalEventInput(
                eventKey = key,
                eventType = OperationalEventType.CYCLE_FINISHED,
                operationId = opId,
                cycleId = 12,
                result = "FAILED",
            ),
        )

        assertTrue(replay is AppendEventResult.Duplicate)
        assertEquals(first.eventId, replay.eventId)
        assertEquals(2, database.operationalEventDao().countAll())
        assertEquals("COMPLETE", database.operationalEventDao().findByEventKey(key)!!.result)
    }

    @Test
    fun appendOperationalEvent_enforcesCorrelationRules() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.appendOperationalEvent(
                    OperationalEventInput("op:1:run:1:result", OperationalEventType.RUN_RESULT, operationId = null),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.appendOperationalEvent(
                    OperationalEventInput("op:1:started", OperationalEventType.OPERATION_STARTED, operationId = 1),
                )
            }
        }
        val schedule = service.appendOperationalEvent(
            OperationalEventInput(
                eventKey = OperationalEventKeys.workerScheduleChanged("AUTO_OFF", now.toEpochMilli()),
                eventType = OperationalEventType.WORKER_SCHEDULE_CHANGED,
                operationId = null,
                result = "AUTO_OFF",
            ),
        )
        assertTrue(schedule is AppendEventResult.Appended)
    }

    @Test
    fun operationalEvent_foreignKeyToOperationIsEnforced() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                service.appendOperationalEvent(
                    OperationalEventInput("op:404:run:1:sync", OperationalEventType.MARKET_SYNC_RESULT, operationId = 404),
                )
            }
        }
    }

    @Test
    fun operationalEvent_forbiddenSecretPayloadCannotEnter() = runBlocking {
        val opId = service.startOperation(manualRequest("req-7")).operationId
        val unsafeMessages = listOf(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature",
            "appkey PSxxxxREALKEYxxxx",
            "request body {\"appsecret\":\"x\"}",
            "token abcdefghijklmnopqrstuvwxyz0123456789ABCD",
            "account 12345678-01",
            "Set-Cookie session=abc",
        )
        unsafeMessages.forEachIndexed { index, message ->
            service.appendOperationalEvent(
                OperationalEventInput(
                    eventKey = "op:$opId:run:$index:sync",
                    eventType = OperationalEventType.MARKET_SYNC_RESULT,
                    operationId = opId,
                    runId = index.toLong(),
                    result = "FAILED",
                    safeMessage = message,
                ),
            )
        }
        val stored = service.findEvents(opId).filter { it.eventType == OperationalEventType.MARKET_SYNC_RESULT }
        assertEquals(unsafeMessages.size, stored.size)
        assertTrue(stored.all { it.safeMessage == SafeLogText.WITHHELD })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.appendOperationalEvent(
                    OperationalEventInput(
                        eventKey = "op:$opId:run:99:sync",
                        eventType = OperationalEventType.MARKET_SYNC_RESULT,
                        operationId = opId,
                        reasonCode = "Bearer abc.def",
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.appendOperationalEvent(
                    OperationalEventInput(
                        eventKey = "op:$opId:authorization=Bearer xyz",
                        eventType = OperationalEventType.MARKET_SYNC_RESULT,
                        operationId = opId,
                    ),
                )
            }
        }
        assertNoSecretPersisted()
    }

    @Test
    fun operationalEventDao_exposesNoMutationBeyondInsert() {
        val methods = com.mirunubi.bjstock.core.database.dao.OperationalEventDao::class.java.declaredMethods
            .map { it.name.lowercase() }
        assertFalse(methods.any { it.contains("update") || it.contains("delete") || it.contains("upsert") })
    }

    @Test
    fun newUncorrelatedAuditAndApiRows_keepNullOperationId() = runBlocking {
        val runId = insertRun()
        val auditId = TradeAuditLogService(database.tradeAuditLogDao()) { now }.append(
            strategyRunId = runId,
            eventType = TradeAuditEventType.EVALUATION_DECIDED,
            eventKey = "evaluation:1:decision",
        )
        val apiId = ApiErrorLogService(database.apiErrorLogDao()) { now }.record(
            provider = ApiErrorProvider.KIS,
            operation = "KIS_DAILY_PRICE",
            errorType = ApiErrorType.HTTP_ERROR,
            safeMessage = "server error",
        )

        assertNull(database.tradeAuditLogDao().findByEventKey("evaluation:1:decision")!!.operationId)
        assertNull(database.apiErrorLogDao().findSince(Instant.EPOCH).single { it.id == apiId }.operationId)
        assertTrue(auditId > 0)

        database.tradeAuditLogDao().insert(
            TradeAuditLogEntity(
                strategyRunId = runId,
                eventType = TradeAuditEventType.ORDER_CREATED,
                eventKey = "order:1:created",
                createdAt = now,
                operationId = 5,
            ),
        )
        assertEquals(5L, database.tradeAuditLogDao().findByEventKey("order:1:created")!!.operationId)
    }

    @Test
    fun auditAndApiRows_insideOperationContext_carryOperationId_andReplayKeepsOriginal() = runBlocking {
        val runId = insertRun()
        val opId = service.startOperation(manualRequest("req-ctx")).operationId
        val audit = TradeAuditLogService(database.tradeAuditLogDao()) { now }
        val api = ApiErrorLogService(database.apiErrorLogDao()) { now }

        val apiId = withContext(ForwardOperationContext(opId)) {
            audit.append(
                strategyRunId = runId,
                eventType = TradeAuditEventType.EVALUATION_DECIDED,
                eventKey = "evaluation:9:decision",
            )
            api.record(ApiErrorProvider.KIS, "KIS_DAILY_PRICE", ApiErrorType.HTTP_ERROR, "server error")
        }
        withContext(ForwardOperationContext(opId + 1)) {
            audit.append(
                strategyRunId = runId,
                eventType = TradeAuditEventType.EVALUATION_DECIDED,
                eventKey = "evaluation:9:decision",
            )
        }

        assertEquals(opId, database.tradeAuditLogDao().findByEventKey("evaluation:9:decision")!!.operationId)
        assertEquals(opId, database.apiErrorLogDao().findSince(Instant.EPOCH).single { it.id == apiId }.operationId)
        assertEquals(1, database.tradeAuditLogDao().findByRun(runId).size)
    }

    private fun workerRequest(workId: String, attempt: Int, scheduleInstanceId: String = SLOT) = StartOperationRequest(
        operationKey = ForwardOperationKeys.worker(scheduleInstanceId, attempt),
        trigger = ForwardOperationTrigger.WORKER,
        throughDate = throughDate,
        workId = workId,
        workAttempt = attempt,
        scheduleInstanceId = scheduleInstanceId,
    )

    private companion object {
        const val SLOT = "auto:2026-09-30:0730:KST"
    }

    private fun manualRequest(requestId: String) = StartOperationRequest(
        operationKey = ForwardOperationKeys.manual(requestId),
        trigger = ForwardOperationTrigger.MANUAL,
        throughDate = throughDate,
    )

    private suspend fun insertRun(): Long {
        val strategyId = database.strategyDao().insertStrategy(
            StrategyEntity(strategyCode = "S", strategyName = "S", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
        )
        val versionId = database.strategyDao().insertVersion(
            StrategyVersionEntity(
                strategyId = strategyId,
                versionNo = 1,
                buyThreshold = 700_000,
                sellThreshold = 400_000,
                status = StrategyVersionStatus.ACTIVE,
                createdAt = Instant.EPOCH,
            ),
        )
        return database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = "r",
                strategyVersionId = versionId,
                runType = RunType.PAPER,
                startDate = throughDate,
                endDate = null,
                initialCash = 1L,
                status = RunStatus.READY,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private fun assertNoSecretPersisted() {
        val forbidden = listOf("secret", "bearer", "authorization", "eyj", "cookie", "12345678", "realkey")
        val raw = database.openHelper.readableDatabase
        listOf(
            "SELECT COALESCE(final_code,'') || ' ' || COALESCE(safe_message,'') FROM forward_operations",
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
}
