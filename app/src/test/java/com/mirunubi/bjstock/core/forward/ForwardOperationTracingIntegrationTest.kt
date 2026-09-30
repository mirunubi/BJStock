package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.kis.RecordingKisAuthLogger
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.ForwardRunResult
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.RunType
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.CreateDailySnapshotUseCase
import com.mirunubi.bjstock.core.paper.PaperTradingPolicy
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.paper.ProcessEvaluationUseCase
import com.mirunubi.bjstock.core.paper.ProcessPendingOrdersUseCase
import com.mirunubi.bjstock.core.paper.VirtualFillService
import com.mirunubi.bjstock.core.strategy.EvaluateStrategyRunUseCase
import com.mirunubi.bjstock.core.strategy.SignalRuleEngine
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationLoader
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationRepository
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coordinator + real [ForwardTestOrchestrator] on in-memory Room: event coverage, correlation,
 * aggregation of unreached runs, Retry Failed Cycle targeting, and trading-result parity with the
 * legacy entry point. The gateway is local-only (no network); the clock is fixed after the 18:00 cutoff.
 */
@RunWith(RobolectricTestRunner::class)
class ForwardOperationTracingIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val harnesses = mutableListOf<Harness>()

    @Before
    fun setUp() {
        harnesses.clear()
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
    }

    // --- event coverage and correlation ---

    @Test
    fun runNow_writesRunSyncAndCycleEvents_correlatedToTheOperation() = runBlocking {
        val h = harness()
        val runId = h.createRun(start = MONDAY, end = WEDNESDAY)
        h.seedPendingBuy(runId)

        val outcome = h.coordinator.runManualNow()

        val op = h.operation(outcome.operationId)
        assertEquals(ForwardOperationStatus.SUCCEEDED, op.status)
        assertEquals(1, op.runsConsidered)
        assertEquals(1, op.runsProcessed)
        assertEquals(3, op.cyclesCompleted)
        val events = h.events(op.id)

        val runResult = events.single { it.eventType == OperationalEventType.RUN_RESULT }
        assertEquals(runId, runResult.runId)
        assertEquals(ForwardRunResult.PROCESSED.name, runResult.result)
        assertEquals("Cycles completed 3, failed 0", runResult.safeMessage)

        val sync = events.single { it.eventType == OperationalEventType.MARKET_SYNC_RESULT }
        assertEquals(runId, sync.runId)
        assertEquals(WEDNESDAY, sync.marketDate)
        assertEquals("SUCCESS", sync.result)
        assertNull(sync.reasonCode)

        val cycles = h.database.forwardTestCycleDao().findRecentByRun(runId, 10).sortedBy { it.marketDate }
        assertEquals(listOf(MONDAY, TUESDAY, WEDNESDAY), cycles.map { it.marketDate })
        val started = events.filter { it.eventType == OperationalEventType.CYCLE_STARTED }
        val finished = events.filter { it.eventType == OperationalEventType.CYCLE_FINISHED }
        assertEquals(cycles.map { it.id }, started.map { it.cycleId })
        assertEquals(cycles.map { it.id }, finished.map { it.cycleId })
        cycles.forEach { cycle ->
            val start = started.single { it.cycleId == cycle.id }
            val end = finished.single { it.cycleId == cycle.id }
            assertEquals("op:${op.id}:cycle:${cycle.id}:attempt:1:started", start.eventKey)
            assertEquals("op:${op.id}:cycle:${cycle.id}:attempt:1:finished", end.eventKey)
            assertEquals(runId, start.runId)
            assertEquals(cycle.marketDate, start.marketDate)
            assertEquals(cycle.marketDate, end.marketDate)
            assertEquals("COMPLETE", end.result)
            assertEquals("Attempt 1; stage COMPLETE", end.safeMessage)
            assertTrue(start.id < end.id)
        }
        assertTrue(events.all { it.operationId == op.id })
    }

    @Test
    fun tradeAuditRows_writtenInsideTheOperation_carryOperationId() = runBlocking {
        val h = harness()
        val runId = h.createRun(start = MONDAY, end = WEDNESDAY)
        h.seedPendingBuy(runId)
        val seeded = h.database.tradeAuditLogDao().findByRun(runId)
        assertTrue(seeded.isNotEmpty())

        val outcome = h.coordinator.runManualNow()

        val audits = h.database.tradeAuditLogDao().findByRun(runId)
        val created = audits.filter { row -> seeded.none { it.id == row.id } }
        assertTrue(created.any { it.eventType == TradeAuditEventType.EXECUTION_FILLED })
        assertTrue(created.any { it.eventType == TradeAuditEventType.EVALUATION_DECIDED })
        assertTrue(created.all { it.operationId == outcome.operationId })
        assertTrue(audits.filter { row -> seeded.any { it.id == row.id } }.all { it.operationId == null })
    }

    @Test
    fun apiErrorRows_writtenInsideTheOperation_carryOperationId() = runBlocking {
        val h = harness()
        h.createRun(start = MONDAY, end = WEDNESDAY)
        h.gateway.recordApiErrorDuringSync = true
        h.apiErrorLog.record(ApiErrorProvider.KIS, "KIS_DAILY_PRICE", ApiErrorType.HTTP_ERROR, "outside")

        val outcome = h.coordinator.runManualNow()

        val rows = h.database.apiErrorLogDao().findSince(Instant.EPOCH)
        assertEquals(2, rows.size)
        assertNull(rows.single { it.safeMessage == "outside" }.operationId)
        assertEquals(outcome.operationId, rows.single { it.safeMessage == "inside" }.operationId)
    }

    @Test
    fun coordinatorRun_producesTheSameTradingResultAsTheLegacyEntryPoint() = runBlocking {
        val legacy = harness()
        val traced = harness()
        listOf(legacy, traced).forEach { h ->
            val runId = h.createRun(start = MONDAY, end = WEDNESDAY)
            h.seedPendingBuy(runId)
        }

        legacy.orchestrator.runForwardTests()
        traced.coordinator.runManualNow()

        TRADING_TABLES.forEach { sql -> assertEquals(sql, legacy.dump(sql), traced.dump(sql)) }
        assertTrue(legacy.dump("SELECT id FROM executions").isNotEmpty())
        assertTrue(legacy.dump("SELECT id FROM portfolio_daily_snapshots").isNotEmpty())
    }

    @Test
    fun repeatedInvocations_doNotDuplicateOrdersExecutionsOrSnapshots() = runBlocking {
        val h = harness()
        val runId = h.createRun(start = MONDAY, end = WEDNESDAY.plusDays(2))
        h.seedPendingBuy(runId)
        h.coordinator.runManualNow()
        val before = TRADING_TABLES.associateWith { h.dump(it) }
        assertEquals(RunStatus.RUNNING, h.database.strategyRunDao().findById(runId)!!.status)

        val again = h.coordinator.runManualNow()
        h.coordinator.runWorker(WORK_ID, 0, SLOT)
        h.coordinator.runWorker(WORK_ID, 0, SLOT)

        TRADING_TABLES.forEach { sql -> assertEquals(sql, before.getValue(sql), h.dump(sql)) }
        assertTrue(h.database.executionDao().countByRun(runId) > 0)
        assertEquals(ForwardOperationStatus.NO_OP, again.status)
        assertEquals(ForwardOutcomeReason.WAITING_FOR_MARKET_DATA.name, again.finalCode)
        assertEquals(3, h.database.forwardOperationDao().countAll())
    }

    @Test
    fun marketSyncResult_onlyForRunsThatAttemptedSync_withFailureCode() = runBlocking {
        val h = harness()
        val runId = h.createRun(start = MONDAY, end = WEDNESDAY)
        h.gateway.failSync = true

        val outcome = h.coordinator.runWorker(WORK_ID, 0, SLOT)

        val sync = h.events(outcome.operationId).single { it.eventType == OperationalEventType.MARKET_SYNC_RESULT }
        assertEquals(runId, sync.runId)
        assertEquals("FAILED", sync.result)
        assertEquals(ForwardErrorCode.NETWORK_FAILURE.name, sync.reasonCode)
        assertEquals(ForwardOperationStatus.BLOCKED, outcome.status)
        assertEquals(WorkerDisposition.RETRY, outcome.disposition)
        assertEquals(0, h.database.forwardTestCycleDao().countByRun(runId))
    }

    // --- unreached runs: a non-retryable block still stops later runs ---

    @Test
    fun blockingRun_isBlockedWithItsCanonicalReason() = runBlocking {
        val s = partialScenario()
        val blocker = s.runResult(s.blockerId)
        assertEquals(ForwardRunResult.BLOCKED.name, blocker.result)
        assertEquals(ForwardOutcomeReason.EMPTY_UNIVERSE.name, blocker.reasonCode)
    }

    @Test
    fun laterRuns_areSkipped() = runBlocking {
        val s = partialScenario()
        s.laterIds.forEach { assertEquals(ForwardRunResult.SKIPPED.name, s.runResult(it).result) }
    }

    @Test
    fun laterRuns_skipReasonIsPriorRunBlocked_withFixedMessage() = runBlocking {
        val s = partialScenario()
        s.laterIds.forEach {
            val event = s.runResult(it)
            assertEquals(ForwardOutcomeReason.PRIOR_RUN_BLOCKED.name, event.reasonCode)
            assertEquals("Skipped because an earlier run blocked the operation", event.safeMessage)
        }
    }

    @Test
    fun laterRuns_areNeverFailed() = runBlocking {
        val s = partialScenario()
        s.laterIds.forEach { assertNotEquals(ForwardRunResult.FAILED.name, s.runResult(it).result) }
    }

    @Test
    fun laterRuns_areNeverBlocked_andAreNotTouched() = runBlocking {
        val s = partialScenario()
        s.laterIds.forEach {
            assertNotEquals(ForwardRunResult.BLOCKED.name, s.runResult(it).result)
            assertEquals(0, s.h.database.forwardTestCycleDao().countByRun(it))
            assertEquals(RunStatus.READY, s.h.database.strategyRunDao().findById(it)!!.status)
        }
        assertEquals(1, s.events.count { it.eventType == OperationalEventType.MARKET_SYNC_RESULT })
    }

    @Test
    fun everySelectedRun_hasExactlyOneRunResult() = runBlocking {
        val s = partialScenario()
        val runResults = s.events.filter { it.eventType == OperationalEventType.RUN_RESULT }
        assertEquals(listOf(s.processedId, s.blockerId) + s.laterIds, runResults.map { it.runId })
    }

    @Test
    fun runsConsidered_countsEverySelectedRun() = runBlocking {
        assertEquals(4, partialScenario().op.runsConsidered)
    }

    @Test
    fun runsProcessed_countsOnlyRunsWhoseProcessingStarted() = runBlocking {
        assertEquals(1, partialScenario().op.runsProcessed)
    }

    @Test
    fun runsSkipped_countsPriorRunBlocked_butNotTheBlocker() = runBlocking {
        assertEquals(2, partialScenario().op.runsSkipped)
    }

    @Test
    fun blockAfterMeaningfulProgress_isPartial() = runBlocking {
        val s = partialScenario()
        assertEquals(ForwardOperationStatus.PARTIAL, s.op.status)
        assertEquals(ForwardOutcomeReason.EMPTY_UNIVERSE.name, s.op.finalCode)
        assertEquals("Processed 1 of 4 run(s); skipped 2; cycles completed 3, failed 0", s.op.safeMessage)
    }

    @Test
    fun firstRunBlock_isBlocked() = runBlocking {
        val h = harness()
        val blocker = h.createEmptyUniverseRun()
        val later = listOf(h.createRun(MONDAY, WEDNESDAY, "L1"), h.createRun(MONDAY, WEDNESDAY, "L2"))

        val outcome = h.coordinator.runManualNow()

        val op = h.operation(outcome.operationId)
        assertEquals(ForwardOperationStatus.BLOCKED, op.status)
        assertEquals(ForwardOutcomeReason.EMPTY_UNIVERSE.name, op.finalCode)
        assertEquals(3, op.runsConsidered)
        assertEquals(0, op.runsProcessed)
        assertEquals(2, op.runsSkipped)
        val results = h.events(op.id).filter { it.eventType == OperationalEventType.RUN_RESULT }
        assertEquals(listOf(blocker) + later, results.map { it.runId })
    }

    @Test
    fun worker_stillReturnsFailure_forNonRetryableAggregateBlock() = runBlocking {
        val s = partialScenario(viaWorker = true)
        assertEquals(WorkerDisposition.FAILURE, s.outcome.disposition)
        assertEquals(ForwardOperationStatus.PARTIAL, s.op.status)
    }

    // --- Retry Failed Cycle against real cycles ---

    @Test
    fun retry_correlatesOperationTargetRunTargetCycleAndNewAttempt() = runBlocking {
        val r = retryScenario()

        val outcome = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId))

        val op = r.h.operation(outcome.operationId)
        assertEquals(ForwardOperationKind.RETRY_FAILED_CYCLE, op.operationKind)
        val events = r.h.events(op.id)
        assertEquals(r.runId, events.single { it.eventType == OperationalEventType.RUN_RESULT }.runId)
        val retried = events.first { it.eventType == OperationalEventType.CYCLE_STARTED }
        assertEquals(r.failedCycleId, retried.cycleId)
        assertEquals(TUESDAY, retried.marketDate)
        assertEquals("op:${op.id}:cycle:${r.failedCycleId}:attempt:2:started", retried.eventKey)
        assertEquals("Attempt 2", retried.safeMessage)
        val newAudits = r.h.database.tradeAuditLogDao().findByRun(r.runId).filter { it.marketDate != null && it.marketDate >= TUESDAY }
        assertTrue(newAudits.isNotEmpty())
        assertTrue(newAudits.all { it.operationId == op.id })
    }

    @Test
    fun retry_success_incrementsAttempt_andContinues() = runBlocking {
        val r = retryScenario()

        r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId))

        val tuesday = r.h.database.forwardTestCycleDao().find(r.runId, TUESDAY)!!
        assertEquals(r.failedCycleId, tuesday.id)
        assertEquals(2, tuesday.attemptCount)
        assertEquals(ForwardCycleStatus.COMPLETE, tuesday.status)
        assertEquals(1, r.h.database.forwardTestCycleDao().find(r.runId, WEDNESDAY)!!.attemptCount)
    }

    @Test
    fun retry_success_hasCorrectSafeFinishResult() = runBlocking {
        val r = retryScenario()

        val outcome = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId))

        val op = r.h.operation(outcome.operationId)
        assertEquals(ForwardOperationStatus.SUCCEEDED, op.status)
        assertNull(op.finalCode)
        assertEquals(1, op.runsConsidered)
        assertEquals(1, op.runsProcessed)
        assertEquals(2, op.cyclesCompleted)
        assertEquals("Processed 1 of 1 run(s); skipped 0; cycles completed 2, failed 0", op.safeMessage)
        assertEquals(listOf(WEDNESDAY), (outcome.display as ForwardOrchestratorResult.Ok).processedDates)
    }

    @Test
    fun retry_createsNoDuplicateCycle_orTradingRows_onRepeatTaps() = runBlocking {
        val r = retryScenario()
        r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId))
        val before = TRADING_TABLES.associateWith { r.h.dump(it) }

        val again = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId))
        val targeted = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId, expectedCycleId = r.failedCycleId))

        assertEquals(ForwardOperationStatus.NO_OP, again.status)
        assertEquals(ForwardOutcomeReason.TARGET_CYCLE_NOT_FOUND.name, again.finalCode)
        assertEquals(ForwardOperationStatus.NO_OP, targeted.status)
        assertEquals(ForwardOutcomeReason.TARGET_CYCLE_NOT_FAILED.name, targeted.finalCode)
        TRADING_TABLES.forEach { sql -> assertEquals(sql, before.getValue(sql), r.h.dump(sql)) }
        assertEquals(3, r.h.database.forwardTestCycleDao().countByRun(r.runId))
        assertEquals(2, r.h.database.forwardTestCycleDao().find(r.runId, TUESDAY)!!.attemptCount)
    }

    @Test
    fun retry_staleOrMismatchedTarget_isNotMutated() = runBlocking {
        val r = retryScenario()
        val otherRun = r.h.createRun(start = MONDAY, end = WEDNESDAY, name = "OTHER")
        val otherCycle = r.h.insertFailedCycle(otherRun, TUESDAY)
        val cyclesBefore = r.h.dump("SELECT * FROM forward_test_cycles ORDER BY id")

        val mismatch = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId, expectedCycleId = otherCycle))
        val notFailed = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId, marketDate = MONDAY))
        val missing = r.h.coordinator.retryFailedCycle(RetryFailedCycleTarget(r.runId, marketDate = FRIDAY))

        assertEquals(ForwardOperationStatus.BLOCKED, mismatch.status)
        assertEquals(ForwardOutcomeReason.TARGET_CYCLE_RUN_MISMATCH.name, mismatch.finalCode)
        assertEquals(ForwardOperationStatus.NO_OP, notFailed.status)
        assertEquals(ForwardOutcomeReason.TARGET_CYCLE_NOT_FAILED.name, notFailed.finalCode)
        assertEquals(ForwardOperationStatus.NO_OP, missing.status)
        assertEquals(ForwardOutcomeReason.TARGET_CYCLE_NOT_FOUND.name, missing.finalCode)
        assertEquals(cyclesBefore, r.h.dump("SELECT * FROM forward_test_cycles ORDER BY id"))
        listOf(mismatch, notFailed, missing).forEach { outcome ->
            val events = r.h.events(outcome.operationId)
            assertFalse(events.any { it.eventType == OperationalEventType.CYCLE_STARTED })
            assertEquals(r.runId, events.single { it.eventType == OperationalEventType.RUN_RESULT }.runId)
        }
    }

    // --- scenarios ---

    private class PartialScenario(
        val h: Harness,
        val processedId: Long,
        val blockerId: Long,
        val laterIds: List<Long>,
        val outcome: ForwardOperationOutcome,
        val op: ForwardOperationEntity,
        val events: List<OperationalEventEntity>,
    ) {
        fun runResult(runId: Long): OperationalEventEntity =
            events.single { it.eventType == OperationalEventType.RUN_RESULT && it.runId == runId }
    }

    private suspend fun partialScenario(viaWorker: Boolean = false): PartialScenario {
        val h = harness()
        val processed = h.createRun(MONDAY, WEDNESDAY, "P")
        val blocker = h.createEmptyUniverseRun()
        val later = listOf(h.createRun(MONDAY, WEDNESDAY, "L1"), h.createRun(MONDAY, WEDNESDAY, "L2"))
        val outcome = if (viaWorker) h.coordinator.runWorker(WORK_ID, 0, SLOT) else h.coordinator.runManualNow()
        return PartialScenario(
            h = h,
            processedId = processed,
            blockerId = blocker,
            laterIds = later,
            outcome = outcome,
            op = h.operation(outcome.operationId),
            events = h.events(outcome.operationId),
        )
    }

    private class RetryScenario(val h: Harness, val runId: Long, val failedCycleId: Long)

    private suspend fun retryScenario(): RetryScenario {
        val h = harness()
        val runId = h.createRun(start = MONDAY, end = WEDNESDAY)
        h.orchestrator.runSingleStrategyRun(runId, throughDate = MONDAY)
        val failedId = h.insertFailedCycle(runId, TUESDAY)
        return RetryScenario(h, runId, failedId)
    }

    private suspend fun harness(): Harness = Harness(context).also {
        harnesses += it
        it.initialize()
    }

    private class LocalGateway(private val apiErrorLog: ApiErrorLogService) : ForwardMarketDataGateway {
        @Volatile var failSync = false
        @Volatile var recordApiErrorDuringSync = false

        override suspend fun ensureCredentials(): Boolean = true

        override suspend fun syncUniverseTo(instrumentIds: List<Long>, throughDate: LocalDate): MarketSyncOutcome {
            if (recordApiErrorDuringSync) {
                apiErrorLog.record(ApiErrorProvider.KIS, "KIS_DAILY_PRICE", ApiErrorType.HTTP_ERROR, "inside")
            }
            if (failSync) {
                return MarketSyncOutcome(
                    success = false,
                    errorCode = ForwardErrorCode.NETWORK_FAILURE.name,
                    errorMessage = "timeout",
                    retryable = true,
                    requestedStart = throughDate,
                )
            }
            return MarketSyncOutcome(success = true, requestedStart = throughDate, unchangedCount = instrumentIds.size)
        }
    }

    private class Harness(context: Context) {
        val database: BJStockDatabase = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val apiErrorLog = ApiErrorLogService(database.apiErrorLogDao(), RecordingKisAuthLogger()) { NOW }
        val gateway = LocalGateway(apiErrorLog)
        private val audit = TradeAuditLogService(database.tradeAuditLogDao()) { NOW }
        private val operationLog = ForwardOperationLogService(database) { NOW }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val requestIds = AtomicInteger()
        private val clock = ForwardTestClock(Clock.fixed(CLOCK_INSTANT, ForwardTestConfig.MARKET_ZONE))
        private val cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH }
        private val policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH }
        private val registry = SystemFactorRegistryFactory.create()
        private val factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        private val strategyService = StrategyVersionService(
            strategyDao = database.strategyDao(),
            factorValues = factorValues,
            registry = registry,
            signalRuleDao = database.strategySignalRuleDao(),
            now = { Instant.EPOCH },
        )
        private val runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = cashLedger,
            policyService = policyService,
            factorRegistry = registry,
            defaultPolicyTemplate = { PaperTradingPolicy.ZERO_COST },
            now = { Instant.EPOCH },
        )
        private val processEvaluation = ProcessEvaluationUseCase(
            database = database,
            evaluationDao = database.stockEvaluationDao(),
            strategyRunDao = database.strategyRunDao(),
            orderDao = database.orderDao(),
            positionDao = database.positionDao(),
            audit = audit,
            now = { Instant.EPOCH },
        )
        val orchestrator = ForwardTestOrchestrator(
            strategyRunDao = database.strategyRunDao(),
            strategyDao = database.strategyDao(),
            universeDao = database.strategyRunInstrumentDao(),
            cycleDao = database.forwardTestCycleDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            orderDao = database.orderDao(),
            evaluationDao = database.stockEvaluationDao(),
            snapshotDao = database.portfolioDailySnapshotDao(),
            factorDao = database.factorDao(),
            policyService = policyService,
            processPending = ProcessPendingOrdersUseCase(
                strategyRunDao = database.strategyRunDao(),
                orderDao = database.orderDao(),
                evaluationDao = database.stockEvaluationDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                positionDao = database.positionDao(),
                cashLedger = cashLedger,
                fills = VirtualFillService(
                    database = database,
                    orderDao = database.orderDao(),
                    executionDao = database.executionDao(),
                    positionDao = database.positionDao(),
                    cashLedger = cashLedger,
                    audit = audit,
                    now = { Instant.EPOCH },
                ),
                policyService = policyService,
            ),
            factorCalculation = FactorCalculationService(
                registry = registry,
                instrumentDao = database.instrumentDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                factorValues = factorValues,
            ),
            evaluateRun = EvaluateStrategyRunUseCase(
                strategyDao = database.strategyDao(),
                strategyRunDao = database.strategyRunDao(),
                evaluations = StrategyEvaluationRepository(database, database.stockEvaluationDao()),
                loader = StrategyEvaluationLoader(
                    strategyService = strategyService,
                    factorDao = database.factorDao(),
                    factorValues = factorValues,
                    signalRuleDao = database.strategySignalRuleDao(),
                    signalRuleEngine = SignalRuleEngine(database.marketDailyBarDao()),
                ),
                audit = audit,
            ),
            processEvaluation = processEvaluation,
            createSnapshot = CreateDailySnapshotUseCase(
                strategyRunDao = database.strategyRunDao(),
                positionDao = database.positionDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                snapshotDao = database.portfolioDailySnapshotDao(),
                cashLedger = cashLedger,
                now = { Instant.EPOCH },
            ),
            marketData = gateway,
            clock = clock,
            apiErrorLog = apiErrorLog,
            now = { Instant.EPOCH },
        )
        val coordinator = ForwardTestExecutionCoordinator(
            executor = orchestrator,
            operationLog = operationLog,
            clock = clock,
            executionScope = scope,
            newRequestId = { "req-${requestIds.incrementAndGet()}" },
        )
        private var instrumentId = 0L
        private var versionId = 0L

        suspend fun initialize() {
            factorValues.ensureSystemFactorDefinitions()
            instrumentId = database.instrumentDao().insert(
                InstrumentEntity(market = "KRX", symbol = "005930", name = "Samsung"),
            )
            versionId = createActiveVersion()
            insertBars()
        }

        suspend fun createRun(start: LocalDate, end: LocalDate, name: String = "FT"): Long =
            runService.createReadyRun(
                strategyVersionId = versionId,
                runName = name,
                startDate = start,
                initialCashWon = 100_000_000L,
                endDate = end,
                instrumentIds = listOf(instrumentId),
            )

        suspend fun createEmptyUniverseRun(): Long = database.strategyRunDao().insert(
            StrategyRunEntity(
                runName = "EMPTY",
                strategyVersionId = versionId,
                runType = RunType.PAPER,
                startDate = MONDAY,
                endDate = WEDNESDAY,
                initialCash = 100_000_000L,
                status = RunStatus.READY,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        suspend fun seedPendingBuy(runId: Long) {
            val evaluationId = database.stockEvaluationDao().insertEvaluation(
                StockEvaluationEntity(
                    strategyRunId = runId,
                    instrumentId = instrumentId,
                    evaluationDate = FRIDAY,
                    quantScore = 800_000,
                    aiScore = null,
                    finalScore = 800_000,
                    quantDecision = TradeDecision.BUY,
                    finalDecision = TradeDecision.BUY,
                    createdAt = Instant.EPOCH,
                ),
            )
            processEvaluation(evaluationId)
        }

        suspend fun insertFailedCycle(runId: Long, date: LocalDate): Long = database.forwardTestCycleDao().insert(
            ForwardTestCycleEntity(
                strategyRunId = runId,
                marketDate = date,
                status = ForwardCycleStatus.FAILED,
                currentStage = ForwardCycleStage.SNAPSHOT,
                attemptCount = 1,
                errorCode = ForwardErrorCode.SNAPSHOT_MISSING_PRICE.name,
                errorMessage = "SNAPSHOT_FAIL",
                retryable = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )

        suspend fun operation(id: Long): ForwardOperationEntity = operationLog.findOperation(id)!!

        suspend fun events(id: Long): List<OperationalEventEntity> = operationLog.findEvents(id)

        fun dump(sql: String): List<List<String?>> =
            database.openHelper.readableDatabase.query(sql).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            }

        fun close() {
            scope.cancel()
            database.close()
        }

        private suspend fun createActiveVersion(): Long {
            val strategyId = strategyService.createStrategy("TRACE", "Trace")
            val draft = strategyService.createDraftVersion(strategyId)
            strategyService.upsertDraftWeight(
                strategyVersionId = draft,
                factorId = factorValues.findDefinitionByCode(FactorCodes.MOMENTUM_20D)!!.id,
                weightStored = 1_000_000,
                enabled = true,
                factorCalculationVersion = "v1",
            )
            check(strategyService.activateStrategyVersion(draft) is StrategyActivationResult.Success)
            return draft
        }

        /** 30 rising warm-up bars before the run so evaluations persist, plus the three run days. */
        private suspend fun insertBars() {
            var date = FRIDAY
            var remaining = 30
            val warmup = mutableListOf<LocalDate>()
            while (remaining > 0) {
                if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                    warmup += date
                    remaining--
                }
                date = date.minusDays(1)
            }
            (warmup.reversed() + listOf(MONDAY, TUESDAY, WEDNESDAY)).forEachIndexed { index, day ->
                val close = 40_000L + index * 500L
                database.marketDailyBarDao().insert(
                    MarketDailyBarEntity(
                        instrumentId = instrumentId,
                        tradeDate = day,
                        openPrice = close - 200,
                        highPrice = close + 100,
                        lowPrice = close - 300,
                        closePrice = close,
                        volume = 1_000,
                        tradingValue = null,
                        source = "TEST",
                        collectedAt = Instant.EPOCH,
                        createdAt = Instant.EPOCH,
                    ),
                )
            }
        }
    }

    private companion object {
        const val WORK_ID = "3f1e2d4c-5b6a-4789-8a9b-0c1d2e3f4a5b"
        const val SLOT = "auto:2026-09-30:0700:KST"
        val FRIDAY: LocalDate = LocalDate.of(2026, 10, 9)
        val MONDAY: LocalDate = LocalDate.of(2026, 10, 12)
        val TUESDAY: LocalDate = LocalDate.of(2026, 10, 13)
        val WEDNESDAY: LocalDate = LocalDate.of(2026, 10, 14)
        val NOW: Instant = Instant.parse("2026-10-14T11:00:00Z")
        val CLOCK_INSTANT: Instant =
            ZonedDateTime.of(2026, 10, 14, 20, 0, 0, 0, ForwardTestConfig.MARKET_ZONE).toInstant()

        val TRADING_TABLES = listOf(
            "SELECT id, strategy_run_id, instrument_id, evaluation_date, quant_score, ai_score, final_score, " +
                "quant_decision, final_decision FROM stock_evaluations ORDER BY id",
            "SELECT * FROM orders ORDER BY id",
            "SELECT * FROM executions ORDER BY id",
            "SELECT * FROM positions ORDER BY id",
            "SELECT * FROM cash_ledger ORDER BY id",
            "SELECT * FROM portfolio_daily_snapshots ORDER BY id",
            "SELECT * FROM forward_test_cycles ORDER BY id",
            "SELECT id, strategy_run_id, event_type, event_key FROM trade_audit_logs ORDER BY id",
        )
    }
}
