package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.FactorDefinitionEntity
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import java.time.LocalDate

@Dao
interface FactorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDefinition(entity: FactorDefinitionEntity): Long

    @Query("SELECT * FROM factor_definitions WHERE factor_code = :factorCode LIMIT 1")
    suspend fun findDefinitionByCode(factorCode: String): FactorDefinitionEntity?

    @Query("SELECT * FROM factor_definitions WHERE id = :id LIMIT 1")
    suspend fun findDefinitionById(id: Long): FactorDefinitionEntity?

    @Query("SELECT * FROM factor_definitions ORDER BY factor_code")
    suspend fun findAllDefinitions(): List<FactorDefinitionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertValue(entity: FactorValueEntity): Long

    @Update
    suspend fun updateValue(entity: FactorValueEntity)

    @Query(
        """
        SELECT * FROM factor_values
        WHERE instrument_id = :instrumentId
          AND factor_id = :factorId
          AND evaluation_date = :evaluationDate
          AND calculation_version = :calculationVersion
        LIMIT 1
        """,
    )
    suspend fun findValue(
        instrumentId: Long,
        factorId: Long,
        evaluationDate: LocalDate,
        calculationVersion: String,
    ): FactorValueEntity?

    @Query(
        """
        SELECT * FROM factor_values
        WHERE instrument_id = :instrumentId
          AND evaluation_date = :evaluationDate
        ORDER BY factor_id
        """,
    )
    suspend fun findValues(
        instrumentId: Long,
        evaluationDate: LocalDate,
    ): List<FactorValueEntity>

    @Query(
        """
        SELECT * FROM factor_values
        WHERE instrument_id = :instrumentId AND factor_id = :factorId
        ORDER BY evaluation_date DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestFactorValue(
        instrumentId: Long,
        factorId: Long,
    ): FactorValueEntity?

    @Query("SELECT COUNT(*) FROM factor_values WHERE instrument_id = :instrumentId")
    suspend fun countValuesByInstrument(instrumentId: Long): Int
}
