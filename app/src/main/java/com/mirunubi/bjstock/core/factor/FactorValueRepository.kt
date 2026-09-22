package com.mirunubi.bjstock.core.factor

import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.entity.FactorDefinitionEntity
import com.mirunubi.bjstock.core.database.entity.FactorValueEntity
import java.time.Instant
import java.time.LocalDate

class FactorValueRepository(
    private val factorDao: FactorDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun ensureSystemFactorDefinitions() {
        val createdAt = now()
        SystemFactorCatalog.definitions.forEach { definition ->
            if (factorDao.findDefinitionByCode(definition.factorCode) == null) {
                factorDao.insertDefinition(
                    FactorDefinitionEntity(
                        factorCode = definition.factorCode,
                        factorName = definition.factorName,
                        category = definition.category,
                        description = definition.description,
                        valueType = definition.valueType,
                        higherIsBetter = definition.higherIsBetter,
                        isActive = true,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                    ),
                )
            }
        }
    }

    suspend fun findDefinitionByCode(factorCode: String): FactorDefinitionEntity? =
        factorDao.findDefinitionByCode(factorCode)

    suspend fun findDefinitionById(id: Long): FactorDefinitionEntity? =
        factorDao.findDefinitionById(id)

    suspend fun findAllDefinitions() = factorDao.findAllDefinitions()

    suspend fun findValues(instrumentId: Long, evaluationDate: LocalDate): List<FactorValueEntity> =
        factorDao.findValues(instrumentId, evaluationDate)

    suspend fun findValue(
        instrumentId: Long,
        factorId: Long,
        evaluationDate: LocalDate,
        calculationVersion: String,
    ): FactorValueEntity? = factorDao.findValue(
        instrumentId = instrumentId,
        factorId = factorId,
        evaluationDate = evaluationDate,
        calculationVersion = calculationVersion,
    )

    suspend fun findLatestFactorValue(instrumentId: Long, factorId: Long): FactorValueEntity? =
        factorDao.findLatestFactorValue(instrumentId, factorId)

    suspend fun countValuesByInstrument(instrumentId: Long): Int =
        factorDao.countValuesByInstrument(instrumentId)

    suspend fun upsertSuccess(
        instrumentId: Long,
        factorId: Long,
        evaluationDate: LocalDate,
        rawValue: String,
        normalizedScore: Long,
        source: String,
        calculationVersion: String,
    ): FactorValueEntity {
        val existing = factorDao.findValue(
            instrumentId = instrumentId,
            factorId = factorId,
            evaluationDate = evaluationDate,
            calculationVersion = calculationVersion,
        )
        return if (existing == null) {
            factorDao.insertValue(
                FactorValueEntity(
                    instrumentId = instrumentId,
                    factorId = factorId,
                    evaluationDate = evaluationDate,
                    rawValue = rawValue,
                    normalizedScore = normalizedScore,
                    source = source,
                    calculationVersion = calculationVersion,
                    createdAt = now(),
                ),
            )
            factorDao.findValue(instrumentId, factorId, evaluationDate, calculationVersion)
                ?: error("inserted factor value missing")
        } else {
            val updated = existing.copy(
                rawValue = rawValue,
                normalizedScore = normalizedScore,
                source = source,
            )
            factorDao.updateValue(updated)
            updated
        }
    }
}
