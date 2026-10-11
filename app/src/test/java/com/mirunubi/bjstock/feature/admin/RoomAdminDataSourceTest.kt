package com.mirunubi.bjstock.feature.admin

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.forward.FakeAutoWorkGateway
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestConfig
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.ForwardTestSchedulerSettings
import com.mirunubi.bjstock.core.kis.KisCredentialDisplay
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisCredentials
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.theme.ThemeService
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomAdminDataSourceTest {
    private val f = AdminFixtures
    private lateinit var database: BJStockDatabase
    private lateinit var gateway: FakeAutoWorkGateway
    private lateinit var kis: FakeKisStore
    private lateinit var source: RoomAdminDataSource
    private var runA = 0L
    private var runB = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = ForwardTestSchedulerSettings(context)
        settings.setAutoEnabled(false)
        gateway = FakeAutoWorkGateway()
        kis = FakeKisStore()
        val clock = ForwardTestClock(Clock.fixed(f.NOW, ForwardTestConfig.MARKET_ZONE))
        val factorValues = FactorValueRepository(database.factorDao()) { Instant.EPOCH }
        val runService = StrategyRunService(
            database = database,
            strategyDao = database.strategyDao(),
            strategyRunDao = database.strategyRunDao(),
            universeDao = database.strategyRunInstrumentDao(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            cashLedger = CashLedgerService(database.cashLedgerDao()) { Instant.EPOCH },
            policyService = PaperTradingPolicyService(database.paperTradingPolicyDao()) { Instant.EPOCH },
            factorRegistry = SystemFactorRegistryFactory.create(),
            themeService = ThemeService(database.themeDao(), database.instrumentDao()) { Instant.EPOCH },
            now = { Instant.EPOCH },
        )
        factorValues.ensureSystemFactorDefinitions()
        source = RoomAdminDataSource(
            scheduler = ForwardTestScheduler(settings, gateway, ForwardOperationLogService(database) { f.NOW }, clock),
            clock = clock,
            operationDao = database.forwardOperationDao(),
            eventDao = database.operationalEventDao(),
            auditDao = database.tradeAuditLogDao(),
            apiErrorLog = ApiErrorLogService(database.apiErrorLogDao(), { }) { f.NOW },
            cycleDao = database.forwardTestCycleDao(),
            runDao = database.strategyRunDao(),
            runService = runService,
            credentialStore = kis,
            settingsStore = kis,
            buildInfo = { AppBuildInfo("com.mirunubi.bjstock", "0.1.0", 1, debuggable = true) },
        )

        val strategyId = database.strategyDao().insertStrategy(StrategyEntity(strategyCode = "MOMENTUM_BASIC", strategyName = "기본 모멘텀 전략"))
        val active = database.strategyDao().insertVersion(
            StrategyVersionEntity(strategyId = strategyId, versionNo = 2, buyThreshold = 1, sellThreshold = 0, status = StrategyVersionStatus.ACTIVE),
        )
        database.strategyDao().insertVersion(
            StrategyVersionEntity(strategyId = strategyId, versionNo = 3, buyThreshold = 1, sellThreshold = 0, status = StrategyVersionStatus.DRAFT),
        )
        runA = database.strategyRunDao().insert(f.run(0, RunStatus.RUNNING, "A").copy(strategyVersionId = active))
        runB = database.strategyRunDao().insert(f.run(0, RunStatus.READY, "B").copy(strategyVersionId = active))
    }

    @After
    fun tearDown() {
        database.close()
    }

    // region DAO queries

    @Test
    fun operationalEvents_findRecent_isGlobal_newestFirst_andLimited() = runBlocking {
        val op = database.forwardOperationDao().insert(f.operation(0).copy(operationKey = "manual:a"))
        val dao = database.operationalEventDao()
        dao.insert(f.event(0, OperationalEventType.OPERATION_STARTED, operationId = op, createdAt = f.NOW.minusSeconds(30)).copy(eventKey = "test:a"))
        dao.insert(f.event(0, OperationalEventType.WORKER_SCHEDULE_CHANGED, createdAt = f.NOW).copy(eventKey = "test:b"))
        dao.insert(f.event(0, OperationalEventType.RUN_RESULT, runId = runA, createdAt = f.NOW.minusSeconds(60)).copy(eventKey = "test:c"))
        dao.insert(f.event(0, OperationalEventType.RUN_RESULT, runId = runB, createdAt = f.NOW).copy(eventKey = "test:d"))

        assertEquals(listOf("test:d", "test:b", "test:a", "test:c"), dao.findRecent().map { it.eventKey })
        assertEquals(listOf("test:d", "test:b"), dao.findRecent(limit = 2).map { it.eventKey })
    }

    @Test
    fun tradeAudits_findRecent_andFindByOperation() = runBlocking {
        val opA = database.forwardOperationDao().insert(f.operation(0).copy(operationKey = "manual:a"))
        val opB = database.forwardOperationDao().insert(f.operation(0).copy(operationKey = "manual:b"))
        val dao = database.tradeAuditLogDao()
        dao.insert(f.audit(0, TradeAuditEventType.ORDER_CREATED, runA, opA, f.NOW.minusSeconds(10)).copy(eventKey = "audit:a"))
        dao.insert(f.audit(0, TradeAuditEventType.EVALUATION_DECIDED, runA, opA, f.NOW.minusSeconds(20)).copy(eventKey = "audit:b"))
        dao.insert(f.audit(0, TradeAuditEventType.EXECUTION_FILLED, runB, opB, f.NOW).copy(eventKey = "audit:c"))
        dao.insert(f.audit(0, TradeAuditEventType.RULE_TRIGGERED, runB, null, f.NOW.minusSeconds(5)).copy(eventKey = "audit:d"))

        assertEquals(listOf("audit:c", "audit:d", "audit:a", "audit:b"), dao.findRecent().map { it.eventKey })
        assertEquals(listOf("audit:c"), dao.findRecent(limit = 1).map { it.eventKey })
        assertEquals(listOf("audit:b", "audit:a"), dao.findByOperation(opA).map { it.eventKey })
        assertEquals(listOf("audit:c"), dao.findByOperation(opB).map { it.eventKey })
        assertTrue(dao.findByOperation(999).isEmpty())
    }

    // endregion

    // region Data source

    @Test
    fun status_readsScheduler_operations_runs_andCredentialPresenceOnly() = runBlocking {
        database.forwardOperationDao().insert(f.operation(0, startedAt = f.NOW.minusSeconds(100)).copy(operationKey = "manual:old"))
        database.forwardOperationDao().insert(f.operation(0, startedAt = f.NOW).copy(operationKey = "manual:new"))
        kis.environment = KisEnvironment.VIRTUAL
        kis.present = setOf(KisEnvironment.VIRTUAL)

        val status = source.status()

        assertFalse(status.auto.autoEnabled)
        assertEquals(f.NOW, status.now)
        assertEquals(listOf("manual:new", "manual:old"), status.recentOperations.map { it.operationKey })
        assertEquals(setOf("A", "B"), status.runs.map { it.runName }.toSet())
        assertEquals(KisPresence(KisEnvironment.VIRTUAL, credentialPresent = true), status.kis)
        assertEquals(0, kis.secretReads)
    }

    @Test
    fun operationDetail_returnsTheOperationWithItsEventsAndAudits() = runBlocking {
        val op = database.forwardOperationDao().insert(f.operation(0, ForwardOperationStatus.FAILED).copy(operationKey = "manual:a"))
        val other = database.forwardOperationDao().insert(f.operation(0).copy(operationKey = "manual:b"))
        database.operationalEventDao().insert(f.event(0, OperationalEventType.OPERATION_FINISHED, operationId = op, createdAt = f.NOW).copy(eventKey = "test:2"))
        database.operationalEventDao().insert(f.event(0, OperationalEventType.OPERATION_STARTED, operationId = op, createdAt = f.NOW.minusSeconds(5)).copy(eventKey = "test:1"))
        database.operationalEventDao().insert(f.event(0, OperationalEventType.OPERATION_STARTED, operationId = other).copy(eventKey = "test:3"))
        database.tradeAuditLogDao().insert(f.audit(0, TradeAuditEventType.ORDER_CREATED, runA, op).copy(eventKey = "audit:1"))

        val detail = source.operationDetail(op)!!

        assertEquals(op, detail.operation.id)
        assertEquals(listOf("test:1", "test:2"), detail.events.map { it.eventKey })
        assertEquals(listOf("audit:1"), detail.audits.map { it.eventKey })
        assertNull(source.operationDetail(999))
    }

    @Test
    fun errors_includeOnlyFailedCycles_andRecentApiErrors() = runBlocking {
        database.forwardTestCycleDao().insert(f.cycle(0, runId = runA).copy(marketDate = f.cycle(0).marketDate.minusDays(1)))
        database.forwardTestCycleDao().insert(f.cycle(0, status = ForwardCycleStatus.COMPLETE, errorCode = null, runId = runA))
        database.forwardTestCycleDao().insert(f.cycle(0, runId = runB))
        database.apiErrorLogDao().insert(f.apiError(0, occurredAt = f.NOW.minusSeconds(60)))
        database.apiErrorLogDao().insert(f.apiError(0, occurredAt = f.NOW.minusSeconds(8L * 24 * 3600)))

        val errors = source.errors()

        assertEquals(2, errors.failedCycles.size)
        assertTrue(errors.failedCycles.all { it.status == ForwardCycleStatus.FAILED })
        assertEquals(1, errors.apiErrors.size)
    }

    @Test
    fun environment_readsBuildInfo_dbVersion_activeVersions_andCredentialPresence() = runBlocking {
        kis.environment = KisEnvironment.PRODUCTION
        kis.present = setOf(KisEnvironment.VIRTUAL)

        val env = source.environment()

        assertEquals("com.mirunubi.bjstock", env.app.packageName)
        assertEquals(BJStockDatabase.VERSION, env.databaseVersion)
        assertEquals(12, env.databaseVersion)
        assertEquals(KisPresence(KisEnvironment.PRODUCTION, credentialPresent = false), env.kis)
        assertEquals(listOf("기본 모멘텀 전략" to 2), env.activeVersions.map { it.strategyName to it.versionNo })
        assertEquals(2, env.runs.size)
        assertEquals(0, kis.secretReads)
    }

    @Test
    fun everyRead_mutatesNothing_andTouchesNoSchedulerOrCredentialWrite() = runBlocking {
        val op = database.forwardOperationDao().insert(f.operation(0).copy(operationKey = "manual:a"))
        database.operationalEventDao().insert(f.event(0, OperationalEventType.OPERATION_STARTED, operationId = op).copy(eventKey = "test:1"))
        database.tradeAuditLogDao().insert(f.audit(0, TradeAuditEventType.ORDER_CREATED, runA, op).copy(eventKey = "audit:1"))
        database.apiErrorLogDao().insert(f.apiError(0, occurredAt = f.NOW.minusSeconds(8L * 24 * 3600)))
        val before = rowCounts()

        source.status()
        source.recentOperations()
        source.operationDetail(op)
        source.audit()
        source.errors()
        source.environment()

        assertEquals(before, rowCounts())
        assertTrue(gateway.calls.isEmpty())
        assertEquals(0, gateway.cancelAllRequests)
        assertEquals(0, kis.writes)
        assertEquals(0, kis.secretReads)
    }

    @Test
    fun packageBuildInfoReader_readsThisPackage() {
        val info = PackageAppBuildInfoReader(ApplicationProvider.getApplicationContext()).read()
        assertEquals("com.mirunubi.bjstock", info.packageName)
        assertTrue(info.versionCode >= 0)
    }

    // endregion

    private fun rowCounts(): Map<String, Int> {
        val tables = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { while (it.moveToNext()) tables += it.getString(0) }
        return tables.associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
        }
    }

    /** Records whether key material is ever loaded; this screen may only ask whether it exists. */
    private class FakeKisStore : KisCredentialStore, KisSettingsStore {
        var environment = KisEnvironment.VIRTUAL
        var present = emptySet<KisEnvironment>()
        var secretReads = 0
        var writes = 0

        override suspend fun saveCredentials(environment: KisEnvironment, appKey: String, appSecret: String) {
            writes++
        }

        override suspend fun hasCredentials(environment: KisEnvironment): Boolean = environment in present

        override suspend fun loadCredentials(environment: KisEnvironment): KisCredentials? {
            secretReads++
            return null
        }

        override suspend fun deleteCredentials(environment: KisEnvironment) {
            writes++
        }

        override suspend fun loadDisplay(environment: KisEnvironment): KisCredentialDisplay {
            secretReads++
            return KisCredentialDisplay(saved = false, appKeyMask = null)
        }

        override suspend fun selectedEnvironment(): KisEnvironment = environment

        override suspend fun setSelectedEnvironment(environment: KisEnvironment) {
            writes++
        }
    }
}
