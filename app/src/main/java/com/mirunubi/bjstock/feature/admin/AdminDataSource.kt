package com.mirunubi.bjstock.feature.admin

import android.content.Context
import android.content.pm.ApplicationInfo
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.ForwardOperationDao
import com.mirunubi.bjstock.core.database.dao.ForwardTestCycleDao
import com.mirunubi.bjstock.core.database.dao.OperationalEventDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.dao.TradeAuditLogDao
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.strategy.ActiveStrategyVersion
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

/** Credential presence only; key material is never loaded or decrypted for this screen. */
data class KisPresence(val environment: KisEnvironment, val credentialPresent: Boolean)

data class AppBuildInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val debuggable: Boolean,
)

data class AdminStatusData(
    val auto: AutoScheduleStatus,
    val now: Instant,
    val recentOperations: List<ForwardOperationEntity>,
    val runs: List<StrategyRunEntity>,
    val kis: KisPresence,
)

data class AdminOperationDetailData(
    val operation: ForwardOperationEntity,
    val events: List<OperationalEventEntity>,
    val audits: List<TradeAuditLogEntity>,
)

data class AdminAuditData(
    val events: List<OperationalEventEntity>,
    val audits: List<TradeAuditLogEntity>,
    val operations: List<ForwardOperationEntity>,
    val apiErrors: List<ApiErrorLogEntity>,
)

data class AdminErrorData(
    val apiErrors: List<ApiErrorLogEntity>,
    val operations: List<ForwardOperationEntity>,
    val failedCycles: List<ForwardTestCycleEntity>,
    val events: List<OperationalEventEntity>,
)

data class AdminEnvironmentData(
    val app: AppBuildInfo,
    val databaseVersion: Int,
    val kis: KisPresence,
    val activeVersions: List<ActiveStrategyVersion>,
    val runs: List<StrategyRunEntity>,
)

/** Read-only access for 운영 · 감사. No method writes, schedules, retries or calls a provider. */
interface AdminDataSource {
    suspend fun status(): AdminStatusData

    suspend fun recentOperations(): List<ForwardOperationEntity>

    suspend fun operationDetail(operationId: Long): AdminOperationDetailData?

    suspend fun audit(): AdminAuditData

    suspend fun errors(): AdminErrorData

    suspend fun environment(): AdminEnvironmentData
}

fun interface AppBuildInfoReader {
    fun read(): AppBuildInfo
}

class PackageAppBuildInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppBuildInfoReader {
    override fun read(): AppBuildInfo {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return AppBuildInfo(
            packageName = context.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }
}

class RoomAdminDataSource @Inject constructor(
    private val scheduler: ForwardTestScheduler,
    private val clock: ForwardTestClock,
    private val operationDao: ForwardOperationDao,
    private val eventDao: OperationalEventDao,
    private val auditDao: TradeAuditLogDao,
    private val apiErrorLog: ApiErrorLogService,
    private val cycleDao: ForwardTestCycleDao,
    private val runDao: StrategyRunDao,
    private val runService: StrategyRunService,
    private val credentialStore: KisCredentialStore,
    private val settingsStore: KisSettingsStore,
    private val buildInfo: AppBuildInfoReader,
) : AdminDataSource {
    override suspend fun status(): AdminStatusData = AdminStatusData(
        auto = scheduler.status(),
        now = clock.nowInstant(),
        recentOperations = operationDao.findRecent(OPERATION_LIMIT),
        runs = runDao.findAll(),
        kis = kisPresence(),
    )

    override suspend fun recentOperations(): List<ForwardOperationEntity> = operationDao.findRecent(OPERATION_LIMIT)

    override suspend fun operationDetail(operationId: Long): AdminOperationDetailData? {
        val operation = operationDao.findById(operationId) ?: return null
        return AdminOperationDetailData(
            operation = operation,
            events = eventDao.findByOperation(operationId),
            audits = auditDao.findByOperation(operationId),
        )
    }

    override suspend fun audit(): AdminAuditData = AdminAuditData(
        events = eventDao.findRecent(EVENT_LIMIT),
        audits = auditDao.findRecent(AUDIT_LIMIT),
        operations = operationDao.findRecent(OPERATION_LIMIT),
        apiErrors = apiErrorLog.findRecentSevenDays(API_ERROR_LIMIT),
    )

    override suspend fun errors(): AdminErrorData = AdminErrorData(
        apiErrors = apiErrorLog.findRecentSevenDays(API_ERROR_LIMIT),
        operations = operationDao.findRecent(OPERATION_LIMIT),
        failedCycles = runDao.findAll().flatMap { run ->
            cycleDao.findRecentByRun(run.id, CYCLE_LIMIT).filter { it.status == ForwardCycleStatus.FAILED }
        },
        events = eventDao.findRecent(EVENT_LIMIT),
    )

    override suspend fun environment(): AdminEnvironmentData = AdminEnvironmentData(
        app = buildInfo.read(),
        databaseVersion = BJStockDatabase.VERSION,
        kis = kisPresence(),
        activeVersions = runService.listActiveVersions(),
        runs = runDao.findAll(),
    )

    private suspend fun kisPresence(): KisPresence {
        val environment = settingsStore.selectedEnvironment()
        return KisPresence(environment, credentialStore.hasCredentials(environment))
    }

    companion object {
        const val OPERATION_LIMIT = 20
        const val EVENT_LIMIT = 100
        const val AUDIT_LIMIT = 100
        const val API_ERROR_LIMIT = 100
        const val CYCLE_LIMIT = 30
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class AdminModule {
    @Binds
    abstract fun bindAdminDataSource(source: RoomAdminDataSource): AdminDataSource

    @Binds
    abstract fun bindAppBuildInfoReader(reader: PackageAppBuildInfoReader): AppBuildInfoReader
}
