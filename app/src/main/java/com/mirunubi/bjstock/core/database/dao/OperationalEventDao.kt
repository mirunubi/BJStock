package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity

/** Append-only: no update or delete. */
@Dao
interface OperationalEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: OperationalEventEntity): Long

    @Query("SELECT * FROM operational_events WHERE event_key = :eventKey LIMIT 1")
    suspend fun findByEventKey(eventKey: String): OperationalEventEntity?

    @Query(
        """
        SELECT * FROM operational_events
        WHERE operation_id = :operationId
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun findByOperation(operationId: Long): List<OperationalEventEntity>

    @Query("SELECT COUNT(*) FROM operational_events")
    suspend fun countAll(): Int
}
