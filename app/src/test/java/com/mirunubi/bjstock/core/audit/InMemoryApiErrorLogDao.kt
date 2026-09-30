package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.database.dao.ApiErrorLogDao
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import java.time.Instant

internal class InMemoryApiErrorLogDao : ApiErrorLogDao {
    val rows = mutableListOf<ApiErrorLogEntity>()
    var insertFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var insertAttempts = 0
        private set

    override suspend fun insert(entity: ApiErrorLogEntity): Long {
        insertAttempts += 1
        insertFailure?.let { throw it }
        val row = entity.copy(id = rows.size + 1L)
        rows += row
        return row.id
    }

    override suspend fun findSince(since: Instant, limit: Int): List<ApiErrorLogEntity> =
        rows.filter { !it.occurredAt.isBefore(since) }.take(limit)

    override suspend fun deleteOlderThan(cutoff: Instant): Int {
        cleanupFailure?.let { throw it }
        val before = rows.size
        rows.removeAll { it.occurredAt.isBefore(cutoff) }
        return before - rows.size
    }

    override suspend fun countAll(): Int = rows.size
}
