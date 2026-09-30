package com.mirunubi.bjstock.core.forward

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** Unfinished Auto work as reported by WorkManager, the scheduling source of truth. */
data class AutoWorkInfo(
    val workId: String,
    val scheduleInstanceId: String?,
    val state: String,
)

/** The only boundary between the Auto scheduler and WorkManager. */
interface AutoWorkGateway {
    suspend fun hasActiveLegacyPeriodic(): Boolean

    suspend fun cancelLegacyPeriodic()

    /** ENQUEUED / RUNNING / BLOCKED work carrying [ForwardTestConfig.AUTO_WORK_TAG]. */
    suspend fun activeAutoWork(): List<AutoWorkInfo>

    /** Unique one-time work per slot with [ExistingWorkPolicy.KEEP]: a pending slot is never duplicated. */
    suspend fun enqueueSlot(slot: AutoScheduleSlot, initialDelayMillis: Long)

    suspend fun cancelAllAuto()
}

class WorkManagerAutoWorkGateway(private val context: Context) : AutoWorkGateway {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override suspend fun hasActiveLegacyPeriodic(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(ForwardTestConfig.LEGACY_PERIODIC_WORK_NAME).first()
            .any { !it.state.isFinished }

    override suspend fun cancelLegacyPeriodic() {
        workManager.cancelUniqueWork(ForwardTestConfig.LEGACY_PERIODIC_WORK_NAME).await()
    }

    override suspend fun activeAutoWork(): List<AutoWorkInfo> =
        workManager.getWorkInfosByTagFlow(ForwardTestConfig.AUTO_WORK_TAG).first()
            .filter { !it.state.isFinished }
            .map { info ->
                AutoWorkInfo(
                    workId = info.id.toString(),
                    scheduleInstanceId = info.tags.firstNotNullOfOrNull(AutoWorkRequests::scheduleInstanceIdFromTag),
                    state = info.state.name,
                )
            }

    override suspend fun enqueueSlot(slot: AutoScheduleSlot, initialDelayMillis: Long) {
        workManager.enqueueUniqueWork(
            slot.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            AutoWorkRequests.build(slot, initialDelayMillis),
        ).await()
    }

    override suspend fun cancelAllAuto() {
        workManager.cancelAllWorkByTag(ForwardTestConfig.AUTO_WORK_TAG).await()
    }
}

object AutoWorkRequests {
    const val KEY_SCHEDULE_INSTANCE_ID = "schedule_instance_id"
    const val KEY_SCHEDULED_AT_EPOCH_MILLIS = "scheduled_at_epoch_millis"
    private const val SLOT_TAG_PREFIX = "bjstock_forward_test_auto_slot="

    /** Backoff is left at the WorkManager default, as it was for the periodic request. */
    fun build(slot: AutoScheduleSlot, initialDelayMillis: Long): OneTimeWorkRequest {
        require(initialDelayMillis > 0L) { "Auto work always waits for a future slot" }
        return OneTimeWorkRequestBuilder<ForwardTestWorker>()
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(ForwardTestConfig.AUTO_WORK_TAG)
            .addTag(SLOT_TAG_PREFIX + slot.scheduleInstanceId)
            .setInputData(inputData(slot))
            .build()
    }

    fun inputData(slot: AutoScheduleSlot): Data = workDataOf(
        KEY_SCHEDULE_INSTANCE_ID to slot.scheduleInstanceId,
        KEY_SCHEDULED_AT_EPOCH_MILLIS to slot.scheduledAt.toEpochMilli(),
    )

    /**
     * The slot this invocation belongs to, or null for legacy periodic work (no id) and malformed input.
     * `scheduled_at_epoch_millis` must agree with the id; nothing is inferred.
     */
    fun slotOf(input: Data): AutoScheduleSlot? {
        val slot = AutoScheduleSlot.parse(input.getString(KEY_SCHEDULE_INSTANCE_ID)) ?: return null
        val scheduledAt = input.getLong(KEY_SCHEDULED_AT_EPOCH_MILLIS, Long.MIN_VALUE)
        return slot.takeIf { scheduledAt == it.scheduledAt.toEpochMilli() }
    }

    fun scheduleInstanceIdFromTag(tag: String): String? =
        tag.takeIf { it.startsWith(SLOT_TAG_PREFIX) }
            ?.removePrefix(SLOT_TAG_PREFIX)
            ?.let(AutoScheduleSlot::parse)
            ?.scheduleInstanceId
}
