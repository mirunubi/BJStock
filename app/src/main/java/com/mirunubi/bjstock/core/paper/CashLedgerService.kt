package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.database.dao.CashLedgerDao
import com.mirunubi.bjstock.core.database.entity.CashLedgerEntity
import com.mirunubi.bjstock.core.error.IntegrityViolationException
import com.mirunubi.bjstock.core.model.CashLedgerEventType
import com.mirunubi.bjstock.core.model.CashLedgerReferenceTypes
import java.time.Instant
import java.time.LocalDate

class CashLedgerService(
    private val cashLedgerDao: CashLedgerDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun currentCash(strategyRunId: Long): Long =
        cashLedgerDao.findLatest(strategyRunId)?.balanceAfter ?: 0L

    suspend fun hasInitialDeposit(strategyRunId: Long): Boolean =
        cashLedgerDao.countByRunAndType(strategyRunId, CashLedgerEventType.INITIAL_DEPOSIT) > 0

    suspend fun appendInitialDeposit(
        strategyRunId: Long,
        amountWon: Long,
        eventDate: LocalDate,
    ): CashLedgerEntity {
        require(amountWon > 0L) { "initial cash must be > 0" }
        require(!hasInitialDeposit(strategyRunId)) {
            "INITIAL_DEPOSIT already exists for run $strategyRunId"
        }
        return append(
            strategyRunId = strategyRunId,
            eventType = CashLedgerEventType.INITIAL_DEPOSIT,
            amountWon = amountWon,
            eventDate = eventDate,
            referenceType = CashLedgerReferenceTypes.STRATEGY_RUN,
            referenceId = strategyRunId,
            eventKey = initialDepositKey(strategyRunId),
        )
    }

    /**
     * Appends one cash event identified by [eventKey]. A replay of the same logical event returns the
     * existing row without a second cash mutation; a different event under the same key aborts.
     */
    suspend fun append(
        strategyRunId: Long,
        eventType: CashLedgerEventType,
        amountWon: Long,
        eventDate: LocalDate,
        referenceType: String?,
        referenceId: Long?,
        eventKey: String,
    ): CashLedgerEntity {
        cashLedgerDao.findByEventKey(eventKey)?.let { existing ->
            val sameEvent = existing.strategyRunId == strategyRunId &&
                existing.eventType == eventType &&
                existing.amount == amountWon &&
                existing.eventDate == eventDate &&
                existing.referenceType == referenceType &&
                existing.referenceId == referenceId
            if (!sameEvent) throw IntegrityViolationException.financial("LEDGER_EVENT_KEY_CONFLICT")
            return existing
        }
        val previous = currentCash(strategyRunId)
        val balanceAfter = previous + amountWon
        require(balanceAfter >= 0L) {
            "cash balance would become negative: $previous + $amountWon"
        }
        val id = cashLedgerDao.insert(
            CashLedgerEntity(
                strategyRunId = strategyRunId,
                eventType = eventType,
                amount = amountWon,
                balanceAfter = balanceAfter,
                referenceType = referenceType,
                referenceId = referenceId,
                eventDate = eventDate,
                createdAt = now(),
                eventKey = eventKey,
            ),
        )
        return cashLedgerDao.findByRun(strategyRunId).first { it.id == id }
    }

    suspend fun reconstructCash(strategyRunId: Long): Long {
        val rows = cashLedgerDao.findByRun(strategyRunId)
        var balance = 0L
        rows.forEach { row ->
            balance += row.amount
            require(balance == row.balanceAfter) {
                "ledger reconstruction mismatch at id=${row.id}"
            }
            require(balance >= 0L)
        }
        return balance
    }

    companion object {
        fun initialDepositKey(strategyRunId: Long) = "run:$strategyRunId:initial-deposit"
        fun buyPrincipalKey(executionId: Long) = "execution:$executionId:buy-principal"
        fun buyCommissionKey(executionId: Long) = "execution:$executionId:buy-commission"
        fun sellProceedsKey(executionId: Long) = "execution:$executionId:sell-proceeds"
        fun sellCommissionKey(executionId: Long) = "execution:$executionId:sell-commission"
        fun sellTaxKey(executionId: Long) = "execution:$executionId:sell-tax"
    }
}
