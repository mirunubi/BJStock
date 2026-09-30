package com.mirunubi.bjstock.core.audit

import com.mirunubi.bjstock.core.database.dao.ApiErrorLogDao
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import java.time.Instant

internal class InMemoryApiErrorLogDao : ApiErrorLogDao {
    val rows = mutableListOf<ApiErrorLogEntity>()

    override suspend fun insert(entity: ApiErrorLogEntity): Long {
        val row = entity.copy(id = rows.size + 1L)
        rows += row
        return row.id
    }

    override suspend fun findSince(since: Instant, limit: Int): List<ApiErrorLogEntity> =
        rows.filter { !it.occurredAt.isBefore(since) }.take(limit)

    override suspend fun deleteOlderThan(cutoff: Instant): Int {
        val before = rows.size
        rows.removeAll { it.occurredAt.isBefore(cutoff) }
        return before - rows.size
    }

    override suspend fun countAll(): Int = rows.size
}
