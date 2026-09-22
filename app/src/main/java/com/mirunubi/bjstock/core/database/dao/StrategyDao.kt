package com.mirunubi.bjstock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyDao {
    @Query("SELECT COUNT(*) FROM strategies")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM strategies")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStrategy(entity: StrategyEntity): Long

    @Query("SELECT * FROM strategies ORDER BY strategy_code")
    suspend fun findAllStrategies(): List<StrategyEntity>

    @Query("SELECT * FROM strategies ORDER BY strategy_code")
    fun observeStrategies(): Flow<List<StrategyEntity>>

    @Query("SELECT * FROM strategies WHERE id = :id LIMIT 1")
    suspend fun findStrategyById(id: Long): StrategyEntity?

    @Query("SELECT * FROM strategies WHERE strategy_code = :code LIMIT 1")
    suspend fun findStrategyByCode(code: String): StrategyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(entity: StrategyVersionEntity): Long

    @Update
    suspend fun updateVersion(entity: StrategyVersionEntity)

    @Query("SELECT * FROM strategy_versions WHERE id = :id LIMIT 1")
    suspend fun findVersionById(id: Long): StrategyVersionEntity?

    @Query(
        """
        SELECT * FROM strategy_versions
        WHERE strategy_id = :strategyId
        ORDER BY version_no
        """,
    )
    suspend fun findVersionsByStrategy(strategyId: Long): List<StrategyVersionEntity>

    @Query(
        """
        SELECT COALESCE(MAX(version_no), 0) FROM strategy_versions
        WHERE strategy_id = :strategyId
        """,
    )
    suspend fun findMaxVersionNo(strategyId: Long): Int

    @Query("UPDATE strategy_versions SET status = :status WHERE id = :id")
    suspend fun updateVersionStatus(id: Long, status: StrategyVersionStatus)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWeight(entity: StrategyFactorWeightEntity): Long

    @Update
    suspend fun updateWeight(entity: StrategyFactorWeightEntity)

    @Query(
        """
        SELECT * FROM strategy_factor_weights
        WHERE strategy_version_id = :strategyVersionId
        ORDER BY factor_id
        """,
    )
    suspend fun findWeights(strategyVersionId: Long): List<StrategyFactorWeightEntity>

    @Query(
        """
        SELECT * FROM strategy_factor_weights
        WHERE strategy_version_id = :strategyVersionId AND factor_id = :factorId
        LIMIT 1
        """,
    )
    suspend fun findWeight(strategyVersionId: Long, factorId: Long): StrategyFactorWeightEntity?
}
