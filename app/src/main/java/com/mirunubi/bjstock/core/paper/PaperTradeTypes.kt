package com.mirunubi.bjstock.core.paper

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Market execution date is stored in [executed_at] as UTC midnight of that trading day.
 * [created_at] remains wall-clock insert time. No extra DATE column in Phase 6.
 */
object MarketExecutionTime {
    fun of(tradeDate: LocalDate): Instant =
        tradeDate.atStartOfDay().toInstant(ZoneOffset.UTC)

    fun toTradeDate(executedAt: Instant): LocalDate =
        executedAt.atZone(ZoneOffset.UTC).toLocalDate()
}

enum class PaperTradeAction {
    NO_TRADE,
    ORDER_CREATED,
    ORDER_ALREADY_EXISTS,
    ORDER_REJECTED,
    FILLED,
    PENDING,
    ALREADY_FILLED,
    SNAPSHOT_CREATED,
    SNAPSHOT_ALREADY_EXISTS,
    SNAPSHOT_FAILED,
}

data class PaperTradeResult(
    val action: PaperTradeAction,
    val message: String? = null,
    val orderId: Long? = null,
    val executionId: Long? = null,
    val snapshotId: Long? = null,
)
